package com.ticketqueue.queue.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.config.QueueProperties
import com.ticketqueue.queue.config.QueueRedisKeys
import com.ticketqueue.queue.dto.QueueDto
import com.ticketqueue.queue.dto.QueueStatus
import com.ticketqueue.queue.exception.QueueException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.data.redis.connection.StringRedisConnection
import org.springframework.data.redis.core.ScanOptions
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
    private val queueLeaveScript: DefaultRedisScript<List<*>>,
    private val rateLimitScript: DefaultRedisScript<Long>,
    private val batchApproveScript: DefaultRedisScript<Long>,
    private val queueProperties: QueueProperties,
    private val meterRegistry: MeterRegistry,
    private val scheduleValidator: ScheduleValidator
) {

    private val logger = KotlinLogging.logger {}

    private val rateLimitFailOpenCounter: Counter =
        meterRegistry.counter("queue.ratelimit.failopen.total")

    private val batchApproveCounter: Counter =
        meterRegistry.counter("queue.batch.approved.total")

    private val enterTimer: Timer = Timer.builder("queue.enter.time")
        .description("대기열 진입 처리 시간")
        .tags("service", "queue")
        .publishPercentileHistogram(true)
        .register(meterRegistry)

    /**
     * 대기열 진입 처리 (REQ-QUEUE-001)
     *
     * Lua 스크립트 반환 코드:
     * - 0: 신규 진입 성공
     * - 1: 동일 회차 중복 진입 (멱등성 — 기존 순위 반환)
     * - 2: 다른 회차 대기 중 → ALREADY_IN_QUEUE
     * - 3: 대기열 가득 참 → QUEUE_FULL
     * - 4: 이미 배치 승인 완료 → ALREADY_APPROVED
     */
    fun enterQueue(userId: UUID, scheduleId: UUID): QueueDto.EnterResponse {
        val sample = Timer.start(meterRegistry)
        try {
            scheduleValidator.validateSchedule(scheduleId)
            val keys = listOf(
                QueueRedisKeys.queue(scheduleId),
                QueueRedisKeys.active(userId),
                QueueRedisKeys.activeSchedules()
            )
            val args = arrayOf(
                userId.toString(),
                System.currentTimeMillis().toString(),
                queueProperties.maxCapacity.toString(),
                queueProperties.activeUser.ttl.toString(),
                scheduleId.toString()
            )

            val result = executeLuaOrThrow(
                queueEnterScript, keys, *args,
                logContext = "enter: scheduleId=$scheduleId, userId=$userId"
            )

            val code = (result.getOrNull(0) as? Long)?.toInt()
                ?: throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 처리 결과를 읽을 수 없습니다.")

            return when (code) {
                0, 1 -> {
                    val rank = safeRank(result)
                    val logMsg = if (code == 0) "Queue entered" else "Queue re-entered (idempotent)"
                    logger.info { "$logMsg: userId=$userId, scheduleId=$scheduleId, rank=$rank" }
                    QueueDto.EnterResponse(
                        status = QueueStatus.WAITING,
                        scheduleId = scheduleId,
                        rank = rank,
                        estimatedWaitTime = calculateWaitTime(rank),
                        token = null
                    )
                }
                2 -> {
                    logger.warn { "Queue enter rejected (already in another queue): userId=$userId, scheduleId=$scheduleId" }
                    throw QueueException(ErrorCode.ALREADY_IN_QUEUE)
                }
                3 -> {
                    logger.warn { "Queue enter rejected (queue full): scheduleId=$scheduleId, capacity=${queueProperties.maxCapacity}" }
                    throw QueueException(ErrorCode.QUEUE_FULL)
                }
                4 -> {
                    logger.info { "Queue enter rejected (already approved): userId=$userId, scheduleId=$scheduleId" }
                    throw QueueException(ErrorCode.ALREADY_APPROVED)
                }
                else -> throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
            }
        } finally {
            sample.stop(enterTimer)
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

        val keys = listOf(QueueRedisKeys.queue(scheduleId), QueueRedisKeys.userToken(userId, scheduleId))
        val result = executeLuaOrThrow(
            queueStatusScript, keys, userId.toString(),
            logContext = "status: scheduleId=$scheduleId, userId=$userId"
        )

        val code = (result.getOrNull(0) as? Long)?.toInt()
            ?: throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 처리 결과를 읽을 수 없습니다.")

        return when (code) {
            0 -> {
                val rank = safeRank(result)
                logger.debug { "Queue status WAITING: userId=$userId, scheduleId=$scheduleId, rank=$rank" }
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
                logger.debug { "Queue status ACTIVE: userId=$userId, scheduleId=$scheduleId" }
                QueueDto.StatusResponse(
                    status = QueueStatus.ACTIVE,
                    rank = 0,
                    estimatedWaitTime = 0,
                    token = token
                )
            }
            2 -> {
                logger.debug { "Queue status NOT_IN_QUEUE: userId=$userId, scheduleId=$scheduleId" }
                throw QueueException(ErrorCode.NOT_IN_QUEUE)
            }
            else -> throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
        }
    }

    /**
     * 대기열 이탈 처리 (REQ-QUEUE-003)
     *
     * Lua 스크립트 반환 코드:
     * - 0: NOT_IN_QUEUE — 대기열에 없거나 다른 회차에 대기 중
     * - 1: 이탈 성공
     */
    fun leaveQueue(userId: UUID, scheduleId: UUID): QueueDto.LeaveResponse {
        val keys = listOf(
            QueueRedisKeys.queue(scheduleId),
            QueueRedisKeys.active(userId),
            QueueRedisKeys.userToken(userId, scheduleId)
        )
        val args = arrayOf(userId.toString(), scheduleId.toString())

        val result = executeLuaOrThrow(
            queueLeaveScript, keys, *args,
            logContext = "leave: scheduleId=$scheduleId, userId=$userId"
        )

        val code = (result.getOrNull(0) as? Long)?.toInt()
            ?: throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 처리 결과를 읽을 수 없습니다.")

        return when (code) {
            0 -> throw QueueException(ErrorCode.NOT_IN_QUEUE)
            1 -> {
                logger.info { "Queue left: userId=$userId, scheduleId=$scheduleId" }
                QueueDto.LeaveResponse(message = "Removed from queue")
            }
            else -> throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
        }
    }

    /**
     * 배치 승인 처리 (REQ-QUEUE-005)
     *
     * 대기열 Sorted Set에서 상위 batchSize명을 원자적으로 추출하고 Queue Token을 발급한다.
     * Token UUID는 Lua 내부에서 생성 불가하므로 Kotlin에서 사전 생성하여 ARGV로 전달한다.
     *
     * @return 실제 승인된 사용자 수 (대기열이 비어 있으면 0)
     */
    fun batchApprove(scheduleId: UUID): Long {
        val batchSize = queueProperties.batch.size
        val tokens = (1..batchSize).map { "qr_${UUID.randomUUID()}" }

        val keys = listOf(
            QueueRedisKeys.queue(scheduleId),
            QueueRedisKeys.activeSchedules()
        )
        val fixedArgs = arrayOf(
            batchSize.toString(),
            scheduleId.toString(),
            queueProperties.token.ttl.toString(),
            queueProperties.activeUser.ttl.toString(),
            System.currentTimeMillis().toString()
        )
        val args = fixedArgs + tokens.toTypedArray()

        return try {
            val approved = stringRedisTemplate.execute(batchApproveScript, keys, *args) ?: 0L
            if (approved > 0) {
                logger.info { "Batch approve completed: scheduleId=$scheduleId, approved=$approved" }
                batchApproveCounter.increment(approved.toDouble())
            }
            approved
        } catch (e: Exception) {
            logger.error(e) { "Batch approve failed: scheduleId=$scheduleId" }
            throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "배치 승인 처리 중 오류가 발생했습니다.", e)
        }
    }

    /**
     * 종료된 회차의 대기열 Redis 키를 정리한다.
     *
     * 삭제 대상:
     * 1. queue:{scheduleId} — 대기열 Sorted Set (TTL 없음, 반드시 삭제)
     * 2. queue:active-schedules에서 SREM — 정합성 보장
     *
     * TTL 10분으로 자동 만료되는 키(queue:active:{userId}, queue:token:{token},
     * queue:user-token:{userId}:{scheduleId})는 24시간 후 이미 만료됨 → 삭제 불필요
     *
     * 파이프라인을 사용하여 SREM + DEL을 단일 round-trip으로 처리한다.
     *
     * @return true if the queue key existed and was deleted
     */
    fun cleanupEndedSchedule(scheduleId: UUID): Boolean {
        val results = stringRedisTemplate.executePipelined { conn ->
            val stringConn = conn as StringRedisConnection
            stringConn.sRem(QueueRedisKeys.activeSchedules(), scheduleId.toString())
            stringConn.del(QueueRedisKeys.queue(scheduleId))
            null
        }
        // results[0] = SREM count (Long), results[1] = DEL count (Long: 1 deleted, 0 not found)
        return (results.getOrNull(1) as? Long ?: 0L) > 0
    }

    /**
     * 관리자 통계 조회 (REQ-QUEUE-007)
     *
     * 활성 대기열 scheduleId 목록을 SMEMBERS로 조회하고,
     * 각 회차별 ZCARD로 대기 인원을 수집하여 통계를 반환한다.
     * KEYS/SCAN 명령 미사용 — queue:active-schedules SET 활용.
     */
    fun getAdminStats(): QueueDto.AdminStatsResponse {
        val activeScheduleIds = getActiveScheduleIds()

        val batchSize = queueProperties.batch.size
        val intervalMs = queueProperties.batch.interval.coerceAtLeast(1).toLong()
        val intervalSeconds = (intervalMs + 999L) / 1000L
        val throughputPerMinute = batchSize.toLong() * 60_000L / intervalMs

        if (activeScheduleIds.isEmpty()) {
            return QueueDto.AdminStatsResponse(
                totalWaiting = 0,
                scheduleStats = emptyList(),
                batchApprovalRate = batchSize,
                batchIntervalSeconds = intervalSeconds,
                throughputPerMinute = throughputPerMinute
            )
        }

        val scheduleStats = activeScheduleIds.map { rawId ->
            val waitingCount = try {
                stringRedisTemplate.opsForZSet().zCard(QueueRedisKeys.queue(rawId)) ?: 0L
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get ZCARD for scheduleId=$rawId" }
                0L
            }
            val activeCount = try {
                countActiveUsers(rawId)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to count active users for scheduleId=$rawId" }
                0L
            }
            QueueDto.ScheduleStat(
                scheduleId = rawId,
                waitingCount = waitingCount,
                activeCount = activeCount,
                tps = batchSize * 1000L / intervalMs
            )
        }

        val totalWaiting = scheduleStats.sumOf { it.waitingCount }

        return QueueDto.AdminStatsResponse(
            totalWaiting = totalWaiting,
            scheduleStats = scheduleStats,
            batchApprovalRate = batchSize,
            batchIntervalSeconds = intervalSeconds,
            throughputPerMinute = throughputPerMinute
        )
    }

    /**
     * 활성 대기열 scheduleId 목록 조회
     *
     * SMEMBERS queue:active-schedules 를 통해 현재 대기 중인 회차 ID를 반환한다.
     * Redis 장애 시 빈 Set을 반환하여 배치 승인 스케줄러가 중단되지 않도록 한다.
     */
    fun getActiveScheduleIds(): Set<String> {
        return try {
            stringRedisTemplate.opsForSet().members(QueueRedisKeys.activeSchedules()) ?: emptySet()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get active schedule IDs" }
            emptySet()
        }
    }

    /**
     * 회차별 활성(승인된) 사용자 수를 SCAN으로 카운트한다.
     *
     * `queue:user-token:*:{scheduleId}` 패턴으로 SCAN하여 유효한 Queue Token 수를 반환한다.
     * 관리자 전용 API에서만 호출되므로 SCAN 사용이 허용된다 (KEYS 명령은 금지).
     * Redis 장애 시 호출부에서 0L로 fallback 처리한다.
     */
    private fun countActiveUsers(scheduleId: String): Long {
        val pattern = "queue:user-token:*:$scheduleId"
        val scanOptions = ScanOptions.scanOptions().match(pattern).count(100).build()
        return stringRedisTemplate.execute { conn ->
            var count = 0L
            conn.scan(scanOptions).use { cursor ->
                while (cursor.hasNext()) {
                    cursor.next()
                    count++
                }
            }
            count
        } ?: 0L
    }

    /**
     * Rate Limit 검사 (REQ-QUEUE-008)
     *
     * Fail-open: Redis 장애 시 요청을 허용하여 Rate Limiter 오류가 서비스 장애로 이어지지 않도록 한다.
     * Fail-open 발생 시 `queue.ratelimit.failopen.total` 카운터를 증가시켜 모니터링 알람 기준으로 사용한다.
     *
     * null 처리: Spring의 execute()는 @Nullable T를 반환한다. Kotlin-Java 제네릭 interop 특성상
     * 컴파일러가 null 불가로 추론하지만, 런타임에서는 null이 반환될 수 있다.
     * Kotlin의 암묵적 null assertion이 NPE를 발생시켜 catch 블록이 처리하나,
     * null 반환 시 명시적 로그를 남기기 위해 null 체크를 추가한다.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun checkRateLimit(userId: UUID) {
        val result = try {
            stringRedisTemplate.execute(
                rateLimitScript,
                listOf(QueueRedisKeys.rateLimit(userId)),
                queueProperties.rateLimit.maxRequests.toString(),
                queueProperties.rateLimit.windowSeconds.toString()
            )
        } catch (e: Exception) {
            logger.warn(e) { "Rate limit check failed (fail-open): userId=$userId" }
            rateLimitFailOpenCounter.increment()
            return
        }
        if (result == null) {
            logger.warn { "Rate limit script returned null (fail-open): userId=$userId" }
            rateLimitFailOpenCounter.increment()
            return
        }
        if (result == 1L) {
            logger.warn { "Rate limit exceeded: userId=$userId" }
            throw QueueException(ErrorCode.RATE_LIMIT_EXCEEDED)
        }
    }

    /**
     * Lua 스크립트를 실행하고 실패 시 INTERNAL_SERVER_ERROR를 던진다.
     * enterQueue와 getQueueStatus의 공통 Redis 실행 패턴을 추출한 헬퍼.
     */
    @Suppress("UNCHECKED_CAST")
    private fun executeLuaOrThrow(
        script: DefaultRedisScript<List<*>>,
        keys: List<String>,
        vararg args: String,
        logContext: String
    ): List<*> {
        return try {
            stringRedisTemplate.execute(script, keys, *args) as List<*>
        } catch (e: Exception) {
            logger.error(e) { "Redis execute failed ($logContext)" }
            throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR, "대기열 서비스에 일시적인 오류가 발생했습니다.", e)
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
            logger.warn {
                "Invalid queue batch config detected — batchSize=${queueProperties.batch.size}, " +
                    "intervalMs=${queueProperties.batch.interval}. Using safe fallback values."
            }
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
