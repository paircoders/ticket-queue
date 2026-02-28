package com.ticketqueue.event.repository

import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatStatus
import java.util.UUID

interface SeatRepositoryCustom {
    fun existsByEventIdAndStatus(eventId: UUID, status: SeatStatus): Boolean
    fun findByScheduleIdOrderByGradeAndSeatNumber(scheduleId: UUID): List<Seat>
    fun findSoldSeatIdsByScheduleId(scheduleId: UUID): List<UUID>
    fun updateStatusToSold(scheduleId: UUID, seatIds: List<UUID>): Long
    fun updateStatusToAvailable(scheduleId: UUID, seatIds: List<UUID>): Long
}
