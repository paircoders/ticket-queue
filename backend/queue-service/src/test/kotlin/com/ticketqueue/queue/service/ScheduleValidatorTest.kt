package com.ticketqueue.queue.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.exception.QueueException
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.util.UUID

@DisplayName("ScheduleValidator 단위 테스트")
class ScheduleValidatorTest {

    private lateinit var eventServiceClient: EventServiceClient
    private lateinit var stringRedisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var scheduleValidator: ScheduleValidator

    private val objectMapper = jacksonObjectMapper()
    private val scheduleId = UUID.randomUUID()
    private val cacheKey = "schedule:sellable:$scheduleId"

    @BeforeEach
    fun setUp() {
        eventServiceClient = mockk()
        stringRedisTemplate = mockk()
        valueOps = mockk(relaxed = true)
        every { stringRedisTemplate.opsForValue() } returns valueOps
        scheduleValidator = ScheduleValidator(eventServiceClient, stringRedisTemplate, objectMapper)
    }

    @Nested
    @DisplayName("캐시 미스 — Feign 호출")
    inner class CacheMiss {

        @BeforeEach
        fun stubCacheMiss() {
            every { valueOps.get(cacheKey) } returns null
        }

        @Test
        @DisplayName("sellable=true이면 예외를 던지지 않는다")
        fun passWhenSellable() {
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = true, reason = null)

            assertDoesNotThrow { scheduleValidator.validateSchedule(scheduleId) }
        }

        @Test
        @DisplayName("sellable=false, reason=TICKET_SALE_NOT_STARTED이면 TICKET_SALE_NOT_STARTED 예외")
        fun throwsWhenSaleNotStarted() {
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = false, reason = "TICKET_SALE_NOT_STARTED")

            val ex = assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }
            assert(ex.errorCode == ErrorCode.TICKET_SALE_NOT_STARTED)
        }

        @Test
        @DisplayName("sellable=false, reason=TICKET_SALE_ENDED이면 TICKET_SALE_ENDED 예외")
        fun throwsWhenSaleEnded() {
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = false, reason = "TICKET_SALE_ENDED")

            val ex = assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }
            assert(ex.errorCode == ErrorCode.TICKET_SALE_ENDED)
        }

        @Test
        @DisplayName("sellable=false, reason=SCHEDULE_NOT_AVAILABLE이면 SCHEDULE_NOT_FOUND 예외")
        fun throwsWhenNotAvailable() {
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = false, reason = "SCHEDULE_NOT_AVAILABLE")

            val ex = assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }
            assert(ex.errorCode == ErrorCode.SCHEDULE_NOT_FOUND)
        }

        @Test
        @DisplayName("Feign이 RESOURCE_NOT_FOUND(404) 예외를 던지면 SCHEDULE_NOT_FOUND 예외")
        fun throwsScheduleNotFoundOnFeign404() {
            every { eventServiceClient.checkSellable(scheduleId) } throws
                BusinessException(ErrorCode.RESOURCE_NOT_FOUND)

            val ex = assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }
            assert(ex.errorCode == ErrorCode.SCHEDULE_NOT_FOUND)
        }

        @Test
        @DisplayName("Feign 호출 실패(네트워크 오류)이면 INTERNAL_SERVER_ERROR 예외 (fail-closed)")
        fun throwsInternalErrorOnFeignFailure() {
            every { eventServiceClient.checkSellable(scheduleId) } throws
                RuntimeException("Connection refused")

            val ex = assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }
            assert(ex.errorCode == ErrorCode.INTERNAL_SERVER_ERROR)
        }

        @Test
        @DisplayName("Feign 호출 실패 시 Feign 클라이언트를 다시 호출하지 않는다")
        fun doesNotRetryOnFeignFailure() {
            every { eventServiceClient.checkSellable(scheduleId) } throws
                RuntimeException("Connection refused")

            assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }

            verify(exactly = 1) { eventServiceClient.checkSellable(scheduleId) }
        }

        @Test
        @DisplayName("Feign 성공 응답 후 Redis 캐시에 저장한다")
        fun cachesSellableResponseAfterFeignCall() {
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = true, reason = null)

            scheduleValidator.validateSchedule(scheduleId)

            verify { valueOps.set(cacheKey, any(), any<java.time.Duration>()) }
        }

        @Test
        @DisplayName("알 수 없는 reason이면 SCHEDULE_NOT_FOUND 예외를 던진다")
        fun throwsScheduleNotFoundOnUnknownReason() {
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = false, reason = "SOMETHING_WEIRD")

            val ex = assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }
            assert(ex.errorCode == ErrorCode.SCHEDULE_NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("캐시 히트")
    inner class CacheHit {

        @Test
        @DisplayName("캐시 히트 시 Feign 클라이언트를 호출하지 않는다")
        fun doesNotCallFeignOnCacheHit() {
            val cached = objectMapper.writeValueAsString(
                EventServiceClient.SellableResponse(sellable = true, reason = null)
            )
            every { valueOps.get(cacheKey) } returns cached

            assertDoesNotThrow { scheduleValidator.validateSchedule(scheduleId) }

            verify(exactly = 0) { eventServiceClient.checkSellable(any()) }
        }

        @Test
        @DisplayName("캐시 히트 sellable=false이면 reason에 따라 예외를 던진다")
        fun throwsOnCachedNotSellable() {
            val cached = objectMapper.writeValueAsString(
                EventServiceClient.SellableResponse(sellable = false, reason = "TICKET_SALE_ENDED")
            )
            every { valueOps.get(cacheKey) } returns cached

            val ex = assertThrows<QueueException> { scheduleValidator.validateSchedule(scheduleId) }
            assert(ex.errorCode == ErrorCode.TICKET_SALE_ENDED)
            verify(exactly = 0) { eventServiceClient.checkSellable(any()) }
        }

        @Test
        @DisplayName("손상된 JSON 캐시는 캐시 미스로 처리하고 Feign을 호출한다")
        fun fallsBackToFeignOnCorruptedCache() {
            every { valueOps.get(cacheKey) } returns "corrupted-json{"
            every { stringRedisTemplate.delete(cacheKey) } returns true
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = true, reason = null)

            assertDoesNotThrow { scheduleValidator.validateSchedule(scheduleId) }

            verify(exactly = 1) { eventServiceClient.checkSellable(scheduleId) }
        }
    }

    @Nested
    @DisplayName("Redis 장애")
    inner class RedisFailure {

        @Test
        @DisplayName("Redis 캐시 읽기 실패 시 Feign을 호출한다 (fail-open)")
        fun callsFeignOnRedisReadFailure() {
            every { valueOps.get(cacheKey) } throws RuntimeException("Redis connection refused")
            every { eventServiceClient.checkSellable(scheduleId) } returns
                EventServiceClient.SellableResponse(sellable = true, reason = null)

            assertDoesNotThrow { scheduleValidator.validateSchedule(scheduleId) }

            verify(exactly = 1) { eventServiceClient.checkSellable(scheduleId) }
        }
    }
}
