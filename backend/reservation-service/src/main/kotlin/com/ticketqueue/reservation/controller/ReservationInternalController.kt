package com.ticketqueue.reservation.controller

import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.exception.ReservationException
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import com.ticketqueue.common.exception.ErrorCode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

// 서비스 간 내부 통신 전용 예매 API
// 보안: InternalApiAuthInterceptor가 X-Service-Api-Key 헤더 검증 (API Gateway는 /internal 경로 차단)
@RestController
@RequestMapping("/internal/reservations")
class ReservationInternalController(
    private val reservationRepository: ReservationRepository,
    private val reservationSeatRepository: ReservationSeatRepository
) {

    @GetMapping("/{reservationId}")
    fun getReservation(@PathVariable reservationId: UUID): ReservationDetailResponse {
        val reservation = reservationRepository.findById(reservationId).orElseThrow {
            ReservationException(ErrorCode.RESERVATION_NOT_FOUND)
        }
        val seatIds = reservationSeatRepository.findByReservationId(reservationId).map { it.seatId }
        return ReservationDetailResponse(
            reservationId = reservation.id!!,
            userId = reservation.userId,
            scheduleId = reservation.scheduleId,
            totalAmount = reservation.totalAmount,
            status = reservation.status,
            holdExpiresAt = reservation.holdExpiresAt,
            seatIds = seatIds
        )
    }

    data class ReservationDetailResponse(
        val reservationId: UUID,
        val userId: UUID,
        val scheduleId: UUID,
        val totalAmount: BigDecimal,
        val status: ReservationStatus,
        val holdExpiresAt: LocalDateTime,
        val seatIds: List<UUID>
    )
}
