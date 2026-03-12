package com.ticketqueue.queue.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.config.QueueProperties
import com.ticketqueue.queue.dto.QueueStatus
import com.ticketqueue.queue.exception.QueueException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.util.UUID

@DisplayName("QueueService 단위 테스트")
class QueueServiceTest {

    private lateinit var stringRedisTemplate: StringRedisTemplate
    private lateinit var queueEnterScript: DefaultRedisScript<List<*>>
    private lateinit var queueStatusScript: DefaultRedisScript<List<*>>
    private lateinit var queueLeaveScript: DefaultRedisScript<List<*>>
    private lateinit var rateLimitScript: DefaultRedisScript<Long>
    private lateinit var batchApproveScript: DefaultRedisScript<Long>
    private lateinit var queueProperties: QueueProperties
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var scheduleValidator: ScheduleValidator
    private lateinit var queueService: QueueService

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        stringRedisTemplate = mockk()
        queueEnterScript = mockk()
        queueStatusScript = mockk()
        queueLeaveScript = mockk()
        rateLimitScript = mockk()
        batchApproveScript = mockk()
        queueProperties = QueueProperties(
            batch = QueueProperties.BatchProperties(size = 10, interval = 1000),
            rateLimit = QueueProperties.RateLimitProperties(maxRequests = 15, windowSeconds = 60)
        )
        meterRegistry = SimpleMeterRegistry()
        scheduleValidator = mockk()
        queueService = QueueService(
            stringRedisTemplate,
            queueEnterScript,
            queueStatusScript,
            queueLeaveScript,
            rateLimitScript,
            batchApproveScript,
            queueProperties,
            meterRegistry,
            scheduleValidator
        )
    }

    @Nested
    @DisplayName("enterQueue")
    inner class EnterQueue {

        @Test
        @DisplayName("정상 진입 시 WAITING 상태와 순위를 반환한다")
        fun returnsWaitingOnSuccess() {
            justRun { scheduleValidator.validateSchedule(scheduleId) }
            every {
                stringRedisTemplate.execute(queueEnterScript, any(), *anyVararg<String>())
            } returns listOf(0L, 4L)

            val result = queueService.enterQueue(userId, scheduleId)

            assertEquals(QueueStatus.WAITING, result.status)
            assertEquals(5L, result.rank)
            assertNull(result.token)
        }

        @Test
        @DisplayName("동일 회차 중복 진입(멱등성)이면 기존 순위를 반환한다")
        fun returnsExistingRankOnDuplicate() {
            justRun { scheduleValidator.validateSchedule(scheduleId) }
            every {
                stringRedisTemplate.execute(queueEnterScript, any(), *anyVararg<String>())
            } returns listOf(1L, 9L)

            val result = queueService.enterQueue(userId, scheduleId)

            assertEquals(QueueStatus.WAITING, result.status)
            assertEquals(10L, result.rank)
        }

        @Test
        @DisplayName("유효하지 않은 scheduleId이면 scheduleValidator에서 SCHEDULE_NOT_FOUND 예외")
        fun throwsScheduleNotFoundOnInvalidSchedule() {
            every { scheduleValidator.validateSchedule(scheduleId) } throws
                QueueException(ErrorCode.SCHEDULE_NOT_FOUND)

            val ex = assertThrows<QueueException> { queueService.enterQueue(userId, scheduleId) }
            assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, ex.errorCode)
            verify(exactly = 0) { stringRedisTemplate.execute(queueEnterScript, any(), *anyVararg<String>()) }
        }

        @Test
        @DisplayName("판매 미시작 회차이면 scheduleValidator에서 TICKET_SALE_NOT_STARTED 예외")
        fun throwsTicketSaleNotStarted() {
            every { scheduleValidator.validateSchedule(scheduleId) } throws
                QueueException(ErrorCode.TICKET_SALE_NOT_STARTED)

            val ex = assertThrows<QueueException> { queueService.enterQueue(userId, scheduleId) }
            assertEquals(ErrorCode.TICKET_SALE_NOT_STARTED, ex.errorCode)
        }

        @Test
        @DisplayName("다른 회차 대기 중이면 ALREADY_IN_QUEUE 예외")
        fun throwsAlreadyInQueue() {
            justRun { scheduleValidator.validateSchedule(scheduleId) }
            every {
                stringRedisTemplate.execute(queueEnterScript, any(), *anyVararg<String>())
            } returns listOf(2L)

            val ex = assertThrows<QueueException> { queueService.enterQueue(userId, scheduleId) }
            assertEquals(ErrorCode.ALREADY_IN_QUEUE, ex.errorCode)
        }
    }

    @Nested
    @DisplayName("getQueueStatus")
    inner class GetQueueStatus {

        @Nested
        @DisplayName("WAITING 상태")
        inner class Waiting {

            @Test
            @DisplayName("rank와 estimatedWaitTime을 올바르게 반환한다")
            fun returnsWaitingWithRankAndWaitTime() {
                stubRateLimitAllow()
                // ZRANK = 9 (0-based) → rank = 10 (1-based)
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(0L, 9L)

                val result = queueService.getQueueStatus(userId, scheduleId)

                assertEquals(QueueStatus.WAITING, result.status)
                assertEquals(10L, result.rank)
                assertEquals(1L, result.estimatedWaitTime) // ceil(10/10) * 1000ms = 1s
                assertNull(result.token)
            }

            @Test
            @DisplayName("첫 번째 대기자(rank=0)의 estimatedWaitTime은 1초이다")
            fun firstInQueueHasOneSecondWait() {
                stubRateLimitAllow()
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(0L, 0L)

                val result = queueService.getQueueStatus(userId, scheduleId)

                assertEquals(1L, result.rank)
                assertEquals(1L, result.estimatedWaitTime)
            }
        }

        @Nested
        @DisplayName("ACTIVE 상태")
        inner class Active {

            @Test
            @DisplayName("token을 반환하고 rank와 estimatedWaitTime은 0이다")
            fun returnsActiveWithToken() {
                val token = "queue-token-abc123"
                stubRateLimitAllow()
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(1L, token)

                val result = queueService.getQueueStatus(userId, scheduleId)

                assertEquals(QueueStatus.ACTIVE, result.status)
                assertEquals(0L, result.rank)
                assertEquals(0L, result.estimatedWaitTime)
                assertEquals(token, result.token)
            }
        }

        @Nested
        @DisplayName("NOT_IN_QUEUE 상태")
        inner class NotInQueue {

            @Test
            @DisplayName("대기열에 없으면 NOT_IN_QUEUE 예외를 던진다")
            fun throwsNotInQueue() {
                stubRateLimitAllow()
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(2L, -1L)

                val exception = assertThrows<QueueException> {
                    queueService.getQueueStatus(userId, scheduleId)
                }
                assertEquals(ErrorCode.NOT_IN_QUEUE, exception.errorCode)
            }
        }

        @Nested
        @DisplayName("Rate Limit")
        inner class RateLimit {

            @Test
            @DisplayName("한도 초과 시 RATE_LIMIT_EXCEEDED 예외를 던진다")
            fun throwsRateLimitExceeded() {
                every {
                    stringRedisTemplate.execute(rateLimitScript, any(), *anyVararg<String>())
                } returns 1L

                val exception = assertThrows<QueueException> {
                    queueService.getQueueStatus(userId, scheduleId)
                }
                assertEquals(ErrorCode.RATE_LIMIT_EXCEEDED, exception.errorCode)
                // Rate limit 초과 시 status 스크립트는 실행되지 않아야 함
                verify(exactly = 0) { stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>()) }
            }

            @Test
            @DisplayName("Rate Limit Redis 장애 시 요청을 허용한다 (fail-open)")
            fun failOpenWhenRedisError() {
                every {
                    stringRedisTemplate.execute(rateLimitScript, any(), *anyVararg<String>())
                } throws RuntimeException("Redis connection refused")
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(0L, 4L)

                val result = queueService.getQueueStatus(userId, scheduleId)

                assertEquals(QueueStatus.WAITING, result.status)
                assertEquals(5L, result.rank)
            }

            @Test
            @DisplayName("Rate Limit Redis 장애 시 fail-open 카운터를 증가시킨다")
            fun incrementsFailOpenCounterWhenRedisError() {
                every {
                    stringRedisTemplate.execute(rateLimitScript, any(), *anyVararg<String>())
                } throws RuntimeException("Redis connection refused")
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(0L, 4L)

                queueService.getQueueStatus(userId, scheduleId)

                val counter = meterRegistry.find("queue.ratelimit.failopen.total").counter()
                assertEquals(1.0, counter?.count())
            }

            @Test
            @DisplayName("Rate Limit 스크립트가 null을 반환하면 fail-open으로 요청을 허용한다")
            fun failOpenWhenRateLimitReturnsNull() {
                every {
                    stringRedisTemplate.execute(rateLimitScript, any(), *anyVararg<String>())
                } throws NullPointerException("execute() returned null (platform type implicit assertion)")
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(0L, 4L)

                val result = queueService.getQueueStatus(userId, scheduleId)

                assertEquals(QueueStatus.WAITING, result.status)
                assertEquals(5L, result.rank)
                val counter = meterRegistry.find("queue.ratelimit.failopen.total").counter()
                assertEquals(1.0, counter?.count())
            }

        }

        @Nested
        @DisplayName("Redis 실행 실패")
        inner class RedisFailure {

            @Test
            @DisplayName("status 스크립트 실행 실패 시 INTERNAL_SERVER_ERROR를 던진다")
            fun throwsInternalErrorWhenStatusScriptFails() {
                stubRateLimitAllow()
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } throws RuntimeException("Redis connection refused")

                val exception = assertThrows<QueueException> {
                    queueService.getQueueStatus(userId, scheduleId)
                }
                assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.errorCode)
            }

            @Test
            @DisplayName("ACTIVE 응답에서 token 위치에 String이 아닌 값이 오면 INTERNAL_SERVER_ERROR를 던진다")
            fun throwsInternalErrorWhenTokenMalformed() {
                stubRateLimitAllow()
                // code=1 (ACTIVE)이지만 token 위치에 Long이 들어온 Lua 버그 시나리오
                every {
                    stringRedisTemplate.execute(queueStatusScript, any(), *anyVararg<String>())
                } returns listOf(1L, 999L)

                val exception = assertThrows<QueueException> {
                    queueService.getQueueStatus(userId, scheduleId)
                }
                assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.errorCode)
            }
        }
    }

    @Nested
    @DisplayName("leaveQueue")
    inner class LeaveQueue {

        @Test
        @DisplayName("대기열 이탈 성공 시 LeaveResponse를 반환한다")
        fun returnsLeaveResponseOnSuccess() {
            every {
                stringRedisTemplate.execute(queueLeaveScript, any(), *anyVararg<String>())
            } returns listOf(1L)

            val result = queueService.leaveQueue(userId, scheduleId)

            assertEquals("Removed from queue", result.message)
        }

        @Test
        @DisplayName("대기열에 없으면 NOT_IN_QUEUE 예외를 던진다")
        fun throwsNotInQueueWhenNotInQueue() {
            every {
                stringRedisTemplate.execute(queueLeaveScript, any(), *anyVararg<String>())
            } returns listOf(0L)

            val exception = assertThrows<QueueException> {
                queueService.leaveQueue(userId, scheduleId)
            }
            assertEquals(ErrorCode.NOT_IN_QUEUE, exception.errorCode)
        }

        @Test
        @DisplayName("Redis 실행 실패 시 INTERNAL_SERVER_ERROR를 던진다")
        fun throwsInternalErrorWhenRedisFailure() {
            every {
                stringRedisTemplate.execute(queueLeaveScript, any(), *anyVararg<String>())
            } throws RuntimeException("Redis connection refused")

            val exception = assertThrows<QueueException> {
                queueService.leaveQueue(userId, scheduleId)
            }
            assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.errorCode)
        }

        @Test
        @DisplayName("예상치 못한 반환 코드 시 INTERNAL_SERVER_ERROR를 던진다")
        fun throwsInternalErrorOnUnexpectedCode() {
            every {
                stringRedisTemplate.execute(queueLeaveScript, any(), *anyVararg<String>())
            } returns listOf(99L)

            val exception = assertThrows<QueueException> {
                queueService.leaveQueue(userId, scheduleId)
            }
            assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("batchApprove")
    inner class BatchApprove {

        @Test
        @DisplayName("승인 성공 시 승인 수를 반환하고 메트릭 카운터를 증가시킨다")
        fun returnsApprovedCountAndIncrementsMetric() {
            every {
                stringRedisTemplate.execute(batchApproveScript, any(), *anyVararg<String>())
            } returns 5L

            val result = queueService.batchApprove(scheduleId)

            assertEquals(5L, result)
            val counter = meterRegistry.find("queue.batch.approved.total").counter()
            assertEquals(5.0, counter?.count())
        }

        @Test
        @DisplayName("대기열이 비어 있으면 0을 반환하고 메트릭을 증가시키지 않는다")
        fun returnsZeroAndDoesNotIncrementMetricWhenEmpty() {
            every {
                stringRedisTemplate.execute(batchApproveScript, any(), *anyVararg<String>())
            } returns 0L

            val result = queueService.batchApprove(scheduleId)

            assertEquals(0L, result)
            // counter가 존재하지 않거나 count=0이어야 함
            val counter = meterRegistry.find("queue.batch.approved.total").counter()
            assertEquals(0.0, counter?.count() ?: 0.0)
        }

        @Test
        @DisplayName("Redis 실행 실패 시 INTERNAL_SERVER_ERROR를 던진다")
        fun throwsInternalErrorWhenRedisFailure() {
            every {
                stringRedisTemplate.execute(batchApproveScript, any(), *anyVararg<String>())
            } throws RuntimeException("Redis connection refused")

            val exception = assertThrows<QueueException> {
                queueService.batchApprove(scheduleId)
            }
            assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.errorCode)
        }

        @Test
        @DisplayName("batchSize만큼의 token UUID를 ARGV에 전달한다")
        fun passesCorrectNumberOfTokenUuidsAsArgs() {
            val capturedArgs = mutableListOf<String>()
            every {
                stringRedisTemplate.execute(batchApproveScript, any(), *anyVararg<String>())
            } answers {
                // invocation.args: [script, keys, arg1, arg2, ...]
                // vararg는 배열로 전달됨
                val varargs = it.invocation.args[2] as Array<*>
                capturedArgs.addAll(varargs.map { a -> a.toString() })
                3L
            }

            queueService.batchApprove(scheduleId)

            // fixedArgs(5개) + tokens(batchSize=10개) = 15개
            val batchSize = queueProperties.batch.size
            assertEquals(5 + batchSize, capturedArgs.size)
            // 마지막 batchSize개의 args가 유효한 UUID 형식인지 검증
            val tokenArgs = capturedArgs.drop(5)
            tokenArgs.forEach { token ->
                UUID.fromString(token) // 파싱 실패 시 IllegalArgumentException
            }
        }
    }

    private fun stubRateLimitAllow() {
        every {
            stringRedisTemplate.execute(rateLimitScript, any(), *anyVararg<String>())
        } returns 0L
    }
}
