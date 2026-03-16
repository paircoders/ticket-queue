package com.ticketqueue.event.scheduler

import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.SeatStatus
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Redis ↔ DB 좌석 재고 정합성 검증 스케줄러 (REQ-EVT-023)
 *
 * ## 목적
 * hold_seats:{scheduleId} Redis SET(선점 중인 좌석)과 DB seats 테이블의 상태를
 * 주기적으로 비교하여 불일치를 감지하고 경고 로그를 남긴다.
 *
 * ## 검증 로직
 * 1. DB에서 UPCOMING/ONGOING 상태 회차 ID 목록 조회
 * 2. 각 회차별 Redis hold_seats:{scheduleId} SET 조회 (SMEMBERS)
 * 3. Redis에 HOLD 중인 seatId가 DB에서 이미 SOLD 상태이면 불일치 (Redis 정리 지연)
 * 4. 불일치 비율 > 10% 시 Redis SET에서 해당 seatId 제거 (보정)
 *
 * ## 설계 원칙
 * - KEYS 명령 절대 금지 — DB에서 active schedule 목록 조회 후 개별 SMEMBERS 사용
 * - fixedDelay 사용 (이전 실행 완료 후 5분 대기, fixedRate와 달리 중복 실행 없음)
 * - Redis 장애 시 예외를 흡수하고 다음 주기에 재시도 (서비스 가용성 우선)
 */
@Component
class SeatConsistencyScheduler(
    private val eventScheduleRepository: EventScheduleRepository,
    private val seatRepository: SeatRepository,
    private val stringRedisTemplate: StringRedisTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val ACTIVE_STATUSES = setOf(ScheduleStatus.UPCOMING, ScheduleStatus.ONGOING)
        private const val HOLD_SEATS_KEY_PREFIX = "hold_seats:"
        private const val INCONSISTENCY_THRESHOLD = 0.10  // 10%
    }

    @Scheduled(fixedDelay = 300_000)
    fun verifyConsistency() {
        log.debug("SeatConsistencyScheduler 실행 시작")

        val activeScheduleIds = try {
            eventScheduleRepository.findIdsByStatusIn(ACTIVE_STATUSES)
        } catch (e: Exception) {
            log.error("SeatConsistencyScheduler: active schedule 조회 실패", e)
            return
        }

        if (activeScheduleIds.isEmpty()) {
            log.debug("SeatConsistencyScheduler: 활성 회차 없음, 검증 생략")
            return
        }

        var totalInconsistencies = 0
        var totalChecked = 0

        for (scheduleId in activeScheduleIds) {
            val inconsistencies = checkSchedule(scheduleId)
            totalInconsistencies += inconsistencies.inconsistentCount
            totalChecked += inconsistencies.checkedCount
        }

        if (totalInconsistencies > 0) {
            log.warn(
                "SeatConsistencyScheduler 완료: schedules=${activeScheduleIds.size}, " +
                "checked=$totalChecked, inconsistencies=$totalInconsistencies"
            )
        } else {
            log.debug(
                "SeatConsistencyScheduler 완료: schedules=${activeScheduleIds.size}, " +
                "checked=$totalChecked, 불일치 없음"
            )
        }
    }

    private fun checkSchedule(scheduleId: UUID): CheckResult {
        val holdKey = "$HOLD_SEATS_KEY_PREFIX$scheduleId"

        val holdSeatIds: Set<String> = try {
            stringRedisTemplate.opsForSet().members(holdKey) ?: emptySet()
        } catch (e: DataAccessException) {
            log.warn("SeatConsistencyScheduler: Redis SMEMBERS 실패 key=$holdKey", e)
            return CheckResult(0, 0)
        }

        if (holdSeatIds.isEmpty()) {
            return CheckResult(0, 0)
        }

        val holdSeatUuids = holdSeatIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (holdSeatUuids.isEmpty()) {
            return CheckResult(0, holdSeatIds.size)
        }

        // DB에서 해당 좌석들의 실제 상태 조회
        val soldSeatIds: Set<UUID> = try {
            seatRepository.findSoldSeatIdsByScheduleId(scheduleId).toSet()
        } catch (e: Exception) {
            log.warn("SeatConsistencyScheduler: DB 조회 실패 scheduleId=$scheduleId", e)
            return CheckResult(0, holdSeatUuids.size)
        }

        // Redis hold_seats에 있지만 DB에서 이미 SOLD인 좌석 → 불일치 (Redis 정리 지연)
        val inconsistentIds = holdSeatUuids.filter { it in soldSeatIds }

        if (inconsistentIds.isNotEmpty()) {
            val ratio = inconsistentIds.size.toDouble() / holdSeatUuids.size
            log.warn(
                "SeatConsistencyScheduler: 불일치 감지 scheduleId=$scheduleId, " +
                "holdCount=${holdSeatUuids.size}, inconsistentCount=${inconsistentIds.size}, " +
                "ratio=${"%.1f".format(ratio * 100)}%, seatIds=${inconsistentIds.take(10)}"
            )

            if (ratio > INCONSISTENCY_THRESHOLD) {
                repairInconsistencies(holdKey, scheduleId, inconsistentIds)
            }
        }

        return CheckResult(inconsistentIds.size, holdSeatUuids.size)
    }

    /**
     * 불일치율 > 10% 시 Redis hold_seats SET에서 이미 SOLD된 좌석 ID를 제거한다.
     *
     * DB가 Source of Truth이므로 DB 기준으로 Redis를 보정한다.
     */
    private fun repairInconsistencies(holdKey: String, scheduleId: UUID, inconsistentIds: List<UUID>) {
        try {
            val members = inconsistentIds.map { it.toString() }.toTypedArray<String>()
            val removed = stringRedisTemplate.opsForSet().remove(holdKey, *members)
            log.warn(
                "SeatConsistencyScheduler: Redis 보정 완료 scheduleId=$scheduleId, " +
                "removed=$removed/${inconsistentIds.size} (불일치율 > 10%)"
            )
        } catch (e: DataAccessException) {
            log.error("SeatConsistencyScheduler: Redis 보정 실패 key=$holdKey", e)
        }
    }

    private data class CheckResult(val inconsistentCount: Int, val checkedCount: Int)
}
