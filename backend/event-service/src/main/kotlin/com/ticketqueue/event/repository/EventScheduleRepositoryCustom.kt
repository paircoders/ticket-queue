package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.EventSchedule
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

/**
 * 커서 기반 페이지네이션을 위한 정리 대상 회차 정보
 * keyset pagination: (eventEndAt, id) 복합 커서
 */
data class ScheduleCleanupCursor(val id: UUID, val eventEndAt: LocalDateTime)

interface EventScheduleRepositoryCustom {
    /**
     * 정리 대상 회차를 커서 기반으로 최대 1000건 조회한다.
     * - 조건: status IN (ENDED, CANCELLED) AND eventEndAt < cutoffTime
     * - 정렬: eventEndAt ASC, id ASC (결정적 순서 보장)
     * - afterEventEndAt, afterId 가 주어지면 해당 커서 이후 행만 반환 (keyset pagination)
     */
    fun findCleanupTargetScheduleIds(
        cutoffTime: LocalDateTime,
        afterEventEndAt: LocalDateTime? = null,
        afterId: UUID? = null
    ): List<ScheduleCleanupCursor>

    fun findByIdWithEvent(id: UUID): Optional<EventSchedule>

    /**
     * 회차 ID 목록을 단일 쿼리로 fetch join 조회한다 (내부 배치 API용)
     *
     * Reservation Service의 예매 내역 batch 조회 시 N+1 회피용.
     * 미존재 ID는 결과에서 제외된다.
     */
    fun findByIdsWithEvent(ids: List<UUID>): List<EventSchedule>
}
