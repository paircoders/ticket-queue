package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SeatRepository : JpaRepository<Seat, UUID>, SeatRepositoryCustom {
    fun existsByEventScheduleIdAndStatus(eventScheduleId: UUID, status: SeatStatus): Boolean
    fun findByEventScheduleIdAndIdIn(eventScheduleId: UUID, ids: List<UUID>): List<Seat>
}
