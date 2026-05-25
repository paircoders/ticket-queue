package com.ticketqueue.event.repository

import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface EventRepositoryCustom {
    /**
     * 공연 목록 조회 (QueryDSL 프로젝션)
     *
     * Event + Venue JOIN + EventSchedule 집계(MIN/MAX startDate)를 단일 쿼리로 처리한다.
     * deleted_at IS NULL 필터를 항상 적용한다.
     */
    fun findEventList(
        pageable: Pageable,
        status: EventStatus?,
        city: String?,
        keyword: String?
    ): Page<EventDto.ListResponse>

    /**
     * 공연 상세 조회 (Venue + Hall fetch join)
     *
     * N+1 문제 방지를 위해 venue와 hall을 단일 쿼리로 함께 로드한다.
     * deleted_at IS NULL 필터를 적용한다.
     */
    fun findEventWithVenueAndHall(eventId: UUID): Event?

    /**
     * 공연 ID 목록을 단일 쿼리로 fetch join 조회한다 (내부 배치 API용)
     *
     * Reservation Service의 예매 내역 batch 조회 시 N+1 회피용.
     * deleted_at IS NULL 필터를 적용하며, 미존재 ID는 결과에서 제외된다.
     */
    fun findEventsWithVenueAndHall(eventIds: List<UUID>): List<Event>

    /**
     * AVAILABLE 좌석이 하나라도 존재하는 회차 ID Set 반환
     *
     * N+1 방지를 위해 회차 ID 목록을 단일 쿼리로 일괄 조회한다.
     * 반환된 Set에 없는 scheduleId는 AVAILABLE 좌석이 없음 (isSoldOut = true).
     */
    fun findScheduleIdsWithAvailableSeats(scheduleIds: List<UUID>): Set<UUID>

    /**
     * 공연을 비관적 락(FOR UPDATE)으로 조회
     *
     * deleteEvent의 race condition 방지: SOLD 좌석 확인과 softDelete 사이의
     * 동시 좌석 판매를 차단하여 데이터 정합성을 보장한다.
     */
    fun findByIdForUpdate(eventId: UUID): Event?
}
