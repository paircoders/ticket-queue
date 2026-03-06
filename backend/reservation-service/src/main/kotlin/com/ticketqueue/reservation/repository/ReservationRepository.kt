package com.ticketqueue.reservation.repository

import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<Reservation>
    fun findByIdAndUserId(id: UUID, userId: UUID): Reservation?
    fun existsByUserIdAndScheduleIdAndStatusIn(userId: UUID, scheduleId: UUID, statuses: List<ReservationStatus>): Boolean
    fun findAllByStatusAndHoldExpiresAtBefore(status: ReservationStatus, now: LocalDateTime): List<Reservation>
}
