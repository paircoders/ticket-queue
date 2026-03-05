package com.ticketqueue.queue.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.config.QueueProperties
import com.ticketqueue.queue.dto.QueueDto
import com.ticketqueue.queue.dto.QueueStatus
import com.ticketqueue.queue.exception.QueueException
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 대기열 비즈니스 로직 서비스
 *
 * Lua 스크립트를 통해 원자적으로 대기열 진입을 처리하며,
 * Lua 반환 코드를 도메인 응답 또는 QueueException으로 변환한다.
 */
@Service
class QueueService(
    private val stringRedisTemplate: StringRedisTemplate,
    private val queueEnterScript: DefaultRedisScript<List<*>>,
    private val queueStatusScript: DefaultRedisScript<List<*>>,
    private val rateLimitScript: DefaultRedisScript<Long>,
    private val queueProperties: QueueProperties
) {

    private val logger = LoggerFactory.getLogger(QueueService::class.java)

    /**
     * 대기열 진입 처리 (REQ-QUEUE-001)
     *
     * Lua 스크립트 반환 코드:
     * - 0: 신규 진입 성공
     * - 1: 동일 회차 중복 진입 (멱등성 — 기존 순위 반환)
     * - 2: 다른 회차 대기 중 → ALREADY_IN_QUEUE
     * - 3: 대기열 가득 참 → QUEUE_FULL
     * - 4: 이미 배치 승인 완료 → ALREADY_APPROVED
     *
     * TODO: scheduleId 유효성 검증 미구현 — 존재하지 않거나 판매 상태가 아닌 회차에 대한 대기열 생성 가능
     *       Event Service 내부 API 호출 또는 Gateway 레벨 검증 필요 (GitHub Issue #163)
     */
    fun enterQueue(userId: UUID, scheduleId: UUID): QueueDto.EnterResponse {
        val queueKey = "queue:$scheduleId"
        val activeKey = "queue:active:$userId"
        val keys = listOf(queueKey, activeKey)

        val args = arrayOf(
            userId.toString(),
            System.currentTimeMillis().toString(),
            queueProperties.maxCapacity.toString(),
            queueProperties.activeUser.ttl.toString(),
            scheduleId.toString()
        )

        @Suppress("UNCHECKED_CAST")
        val result = try {
            stringRedisTemplate.execute(queueEnterScript, keys, *args) as List<*>
        } catch (e: Exception) {
            logger.error("Redis execute failed: scheduleId={}, userId={}", scheduleId, userId, e)
            throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 서비스에 일시적인 오류가 발생했습니다.", e)
        }

        val code = (result.getOrNull(0) as? Long)?.toInt()
            ?: throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 처리 결과를 읽을 수 없습니다.")

        return when (code) {
            0, 1 -> {
                val rank = safeRank(result)
                val logMsg = if (code == 0) "Queue entered" else "Queue re-entered (idempotent)"
                logger.info("$logMsg: userId={}, scheduleId={}, rank={}", userId, scheduleId, rank)
                QueueDto.EnterResponse(
                    status = QueueStatus.WAITING,
                    scheduleId = scheduleId,
                    rank = rank,
                    estimatedWaitTime = calculateWaitTime(rank),
                    token = null
                )
            }
            2 -> {
                logger.warn("Queue enter rejected (already in another queue): userId={}, scheduleId={}", userId, scheduleId)
                throw QueueException(ErrorCode.ALREADY_IN_QUEUE)
            }
            3 -> {
                logger.warn("Queue enter rejected (queue full): scheduleId={}, capacity={}", scheduleId, queueProperties.maxCapacity)
                throw QueueException(ErrorCode.QUEUE_FULL)
            }
            4 -> {
                logger.info("Queue enter rejected (already approved): userId={}, scheduleId={}", userId, scheduleId)
                throw QueueException(ErrorCode.ALREADY_APPROVED)
            }
            else -> throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
        }
    }

    /**
     * 대기열 상태 조회 (REQ-QUEUE-002)
     *
     * Lua 스크립트 반환 코드:
     * - 0: WAITING — 대기 중 (rank 반환)
     * - 1: ACTIVE  — 배치 승인 완료 (token 반환)
     * - 2: NOT_IN_QUEUE — 대기열에 없음
     *
     * Rate Limit: 15회/분 (REQ-QUEUE-008), Fail-open 정책
     */
    fun getQueueStatus(userId: UUID, scheduleId: UUID): QueueDto.StatusResponse {
        checkRateLimit(userId)

        val queueKey = "queue:$scheduleId"
        val userTokenKey = "queue:user-token:$userId:$scheduleId"
        val keys = listOf(queueKey, userTokenKey)

        @Suppress("UNCHECKED_CAST")
        val result = try {
            stringRedisTemplate.execute(queueStatusScript, keys, userId.toString()) as List<*>
        } catch (e: Exception) {
            logger.error("Redis execute failed (status): scheduleId={}, userId={}", scheduleId, userId, e)
            throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 서비스에 일시적인 오류가 발생했습니다.", e)
        }

        val code = (result.getOrNull(0) as? Long)?.toInt()
            ?: throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 처리 결과를 읽을 수 없습니다.")

        return when (code) {
            0 -> {
                val rank = safeRank(result)
                logger.debug("Queue status WAITING: userId={}, scheduleId={}, rank={}", userId, scheduleId, rank)
                QueueDto.StatusResponse(
                    status = QueueStatus.WAITING,
                    rank = rank,
                    estimatedWaitTime = calculateWaitTime(rank),
                    token = null
                )
            }
            1 -> {
                val token = result.getOrNull(1) as? String
                    ?: throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "토큰 정보를 읽을 수 없습니다.")
                logger.debug("Queue status ACTIVE: userId={}, scheduleId={}", userId, scheduleId)
                QueueDto.StatusResponse(
                    status = QueueStatus.ACTIVE,
                    rank = 0,
                    estimatedWaitTime = 0,
                    token = token
                )
            }
            2 -> {
                logger.debug("Queue status NOT_IN_QUEUE: userId={}, scheduleId={}", userId, scheduleId)
                throw QueueException(ErrorCode.NOT_IN_QUEUE)
            }
            else -> throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
        }
    }

    /**
     * Rate Limit 검사 (REQ-QUEUE-008)
     *
     * Fail-open: Redis 장애 시 요청을 허용하여 Rate Limiter 오류가 서비스 장애로 이어지지 않도록 한다.
     */
    private fun checkRateLimit(userId: UUID) {
        val rateLimitKey = "rate:queue-status:$userId"
        val result = try {
            stringRedisTemplate.execute(
                rateLimitScript,
                listOf(rateLimitKey),
                queueProperties.rateLimit.maxRequests.toString(),
                queueProperties.rateLimit.windowSeconds.toString()
            )
        } catch (e: Exception) {
            logger.warn("Rate limit check failed (fail-open): userId={}", userId, e)
            return
        }
        if (result == 1L) {
            logger.warn("Rate limit exceeded: userId={}", userId)
            throw QueueException(ErrorCode.RATE_LIMIT_EXCEEDED)
        }
    }

    /**
     * 예상 대기 시간 계산 (초)
     *
     * 배치 처리 단위: batchSize명 / interval ms마다 승인
     * 밀리초 단위로 먼저 계산한 후 올림(ceiling)하여 초로 변환
     * interval < 1000ms인 경우(예: 500ms)에도 0초 반환 버그 방지
     *
     * 설정값 방어:
     * - batchSize <= 0 → 1로 보정 (divide-by-zero 방지)
     * - intervalMs < 0 → 0으로 보정 (음수 대기시간 방지, interval=0은 "즉시 처리"로 허용)
     */
    private fun calculateWaitTime(rank: Long): Long {
        val batchSize = queueProperties.batch.size.toLong().coerceAtLeast(1)
        val intervalMs = queueProperties.batch.interval.coerceAtLeast(0)
        if (batchSize != queueProperties.batch.size.toLong() || intervalMs != queueProperties.batch.interval) {
            logger.warn(
                "Invalid queue batch config detected — batchSize={}, intervalMs={}. Using safe fallback values.",
                queueProperties.batch.size, queueProperties.batch.interval
            )
        }
        val batchCount = (rank + batchSize - 1) / batchSize
        val waitMs = batchCount * intervalMs
        return (waitMs + 999) / 1000
    }

    /**
     * Lua 결과 리스트에서 rank를 안전하게 추출한다.
     * result[1]이 없거나 Long이 아닌 경우 예외를 던져 Lua 스크립트 버그를 즉시 노출한다.
     */
    private fun safeRank(result: List<*>): Long {
        val rawRank = result.getOrNull(1) as? Long
            ?: throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 순위 정보를 읽을 수 없습니다.")
        return rawRank + 1
    }
}
