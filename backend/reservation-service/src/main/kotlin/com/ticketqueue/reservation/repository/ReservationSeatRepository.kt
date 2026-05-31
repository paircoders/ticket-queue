package com.ticketqueue.reservation.repository

import com.ticketqueue.reservation.entity.ReservationSeat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ReservationSeatRepository : JpaRepository<ReservationSeat, UUID> {
    fun findByReservationId(reservationId: UUID): List<ReservationSeat>
    fun findByReservationIdIn(reservationIds: List<UUID>): List<ReservationSeat>
    fun countByReservationIdIn(reservationIds: List<UUID>): Int

    @Modifying
    @Query("DELETE FROM ReservationSeat rs WHERE rs.reservationId = :reservationId")
    fun deleteAllByReservationId(reservationId: UUID)
}
