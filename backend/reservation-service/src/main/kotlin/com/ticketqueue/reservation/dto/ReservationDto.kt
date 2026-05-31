package com.ticketqueue.reservation.dto

import com.ticketqueue.reservation.entity.ReservationStatus
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

class ReservationDto {

    data class HoldRequest(
        @field:NotNull val scheduleId: UUID,
        @field:Size(min = 1, max = 4) val seatIds: List<UUID>
    )

    data class HoldResponse(
        val reservationId: UUID,
        val status: ReservationStatus,
        val totalAmount: BigDecimal,
        val holdExpiresAt: OffsetDateTime
    )

    data class ChangeSeatsRequest(
        @field:Size(min = 1, max = 4) val newSeatIds: List<UUID>
    )

    data class ChangeSeatsResponse(
        val reservationId: UUID,
        val status: ReservationStatus,
        val newTotalAmount: BigDecimal,
        val holdExpiresAt: OffsetDateTime
    )

    data class CancelResponse(
        val id: UUID,
        val status: ReservationStatus,
        val refundAmount: BigDecimal
    )

    data class SeatStatusResponse(
        val scheduleId: UUID,
        val seats: SeatSummary,
        val sold: List<UUID>,
        val hold: List<UUID>
    ) {
        data class SeatSummary(val total: Long, val available: Long, val sold: Int, val hold: Int)
    }

    data class ReservationListItem(
        val reservationId: UUID,
        val eventTitle: String,
        val scheduleDate: LocalDateTime,
        val status: ReservationStatus,
        val seats: List<SeatInfo>,
        val paymentAmount: BigDecimal
    ) {
        data class SeatInfo(val seatNumber: String, val grade: String)
    }

    data class ReservationsListResponse(
        val list: List<ReservationListItem>
    )

    data class ReservationDetailResponse(
        val reservationId: UUID,
        val eventId: UUID,
        val eventTitle: String,
        val artist: String,
        val venueName: String,
        val hallName: String,
        val scheduleDate: LocalDateTime,
        val status: ReservationStatus,
        val seats: List<SeatDetail>,
        val totalAmount: BigDecimal,
        val paymentId: UUID?,
        val ticketNumber: String?,
        val qrData: String?,
        val createdAt: LocalDateTime
    ) {
        data class SeatDetail(
            val seatId: UUID,
            val seatNumber: String,
            val grade: String,
            val price: BigDecimal
        )
    }
}
