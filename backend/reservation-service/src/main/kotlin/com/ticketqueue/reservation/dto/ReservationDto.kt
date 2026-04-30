package com.ticketqueue.reservation.dto

import com.ticketqueue.reservation.entity.ReservationStatus
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class ReservationDto {

    data class HoldRequest(
        @field:NotNull val scheduleId: UUID,
        @field:NotEmpty @field:Size(min = 1, max = 4) val seatIds: List<UUID>
    )

    data class HoldResponse(
        val reservationId: UUID,
        val status: ReservationStatus,
        val totalAmount: BigDecimal,
        val holdExpiresAt: OffsetDateTime
    )

    data class SeatStatusResponse(
        val scheduleId: UUID,
        val seats: SeatSummary,
        val sold: List<UUID>,
        val hold: List<UUID>
    ) {
        data class SeatSummary(val total: Long, val available: Int, val sold: Int, val hold: Int)
    }
}
