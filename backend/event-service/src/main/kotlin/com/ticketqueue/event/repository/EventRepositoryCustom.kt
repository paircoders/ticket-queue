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
     * AVAILABLE 좌석이 하나라도 존재하는 회차 ID Set 반환
     *
     * N+1 방지를 위해 회차 ID 목록을 단일 쿼리로 일괄 조회한다.
     * 반환된 Set에 없는 scheduleId는 AVAILABLE 좌석이 없음 (isSoldOut = true).
     */
    fun findScheduleIdsWithAvailableSeats(scheduleIds: List<UUID>): Set<UUID>
}
