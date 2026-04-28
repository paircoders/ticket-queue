package com.ticketqueue.reservation.repository

import com.ticketqueue.reservation.entity.ReservationSeat
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReservationSeatRepository : JpaRepository<ReservationSeat, UUID> {
    fun findByReservationId(reservationId: UUID): List<ReservationSeat>
    fun countByReservationIdIn(reservationIds: List<UUID>): Int
}
