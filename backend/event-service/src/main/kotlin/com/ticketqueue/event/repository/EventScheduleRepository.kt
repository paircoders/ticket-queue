package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.EventSchedule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
interface EventScheduleRepository : JpaRepository<EventSchedule, UUID> {
    fun findByEventIdOrderByPlaySequence(eventId: UUID): List<EventSchedule>

    /**
     * 주어진 공연의 회차 중 판매가 이미 시작된 회차가 존재하는지 확인한다.
     * updateEvent의 hasSaleStarted 체크를 위해 전체 목록 로드 대신 EXISTS 쿼리 1개로 처리한다.
     */
    fun existsByEventIdAndSaleStartAtLessThanEqual(eventId: UUID, dateTime: LocalDateTime): Boolean
}
