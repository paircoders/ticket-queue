package com.ticketqueue.reservation.dto

import com.ticketqueue.reservation.entity.ReservationStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class ReservationDetailResponse(
    val reservationId: UUID,
    val userId: UUID,
    val scheduleId: UUID,
    val totalAmount: BigDecimal,
    val status: ReservationStatus,
    val holdExpiresAt: LocalDateTime,
    val seatIds: List<UUID>
)
