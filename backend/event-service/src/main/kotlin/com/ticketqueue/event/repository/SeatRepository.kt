package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SeatRepository : JpaRepository<Seat, UUID> {
    fun existsByEventScheduleIdAndStatus(eventScheduleId: UUID, status: SeatStatus): Boolean

    /** 공연 전체(모든 회차)에 특정 상태의 좌석 존재 여부 확인 — exists 최적화 (COUNT 대비 조기 종료) */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM Seat s WHERE s.eventSchedule.event.id = :eventId AND s.status = :status")
    fun existsByEventIdAndStatus(@Param("eventId") eventId: UUID, @Param("status") status: SeatStatus): Boolean
}
