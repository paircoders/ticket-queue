package com.ticketqueue.reservation.controller

import com.ticketqueue.reservation.dto.ReservationDto.CancelResponse
import com.ticketqueue.reservation.dto.ReservationDto.ChangeSeatsRequest
import com.ticketqueue.reservation.dto.ReservationDto.ChangeSeatsResponse
import com.ticketqueue.reservation.dto.ReservationDto.HoldRequest
import com.ticketqueue.reservation.dto.ReservationDto.HoldResponse
import com.ticketqueue.reservation.dto.ReservationDto.ReservationDetailResponse
import com.ticketqueue.reservation.dto.ReservationDto.ReservationsListResponse
import com.ticketqueue.reservation.dto.ReservationDto.SeatStatusResponse
import com.ticketqueue.reservation.service.ReservationService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/reservations")
class ReservationController(
    private val reservationService: ReservationService
) {

    private val log = KotlinLogging.logger {}

    companion object {
        private const val HEADER_USER_ID = "X-User-Id"
        private const val HEADER_QUEUE_TOKEN = "X-Queue-Token"
    }

    /**
     * 내 예매 목록 조회 (REQ-RSV-009)
     *
     * Gateway가 JWT를 검증하고 X-User-Id 헤더로 userId를 주입한다.
     */
    @GetMapping
    fun getMyReservations(
        @RequestHeader(HEADER_USER_ID) userId: UUID
    ): ReservationsListResponse {
        return reservationService.getMyReservations(userId)
    }

    /**
     * 예매 상세 조회 (REQ-RSV-009)
     *
     * Gateway가 JWT를 검증하고 X-User-Id 헤더로 userId를 주입한다.
     * 소유권 검증 실패 시 404 반환 (정보 노출 방지).
     */
    @GetMapping("/{reservationId}")
    fun getReservationDetail(
        @RequestHeader(HEADER_USER_ID) userId: UUID,
        @PathVariable reservationId: UUID
    ): ReservationDetailResponse {
        return reservationService.getReservationDetail(userId, reservationId)
    }

    /**
     * 좌석 상태 조회 (REQ-RSV-003)
     *
     * Gateway가 JWT를 검증하고 X-User-Id 헤더로 userId를 주입한다.
     * Queue Token은 X-Queue-Token 헤더로 전달되며, Reservation Service에서 Redis 직접 조회로 검증한다.
     */
    @GetMapping("/seats/{scheduleId}")
    fun getSeatStatus(
        @RequestHeader(HEADER_USER_ID) userId: UUID,
        @RequestHeader(HEADER_QUEUE_TOKEN) queueToken: String,
        @PathVariable scheduleId: UUID
    ): SeatStatusResponse {
        return reservationService.getSeatStatus(userId, scheduleId, queueToken)
    }

    /**
     * 좌석 선점 (REQ-RSV-001)
     *
     * Gateway가 JWT를 검증하고 X-User-Id 헤더로 userId를 주입한다.
     * Queue Token은 X-Queue-Token 헤더로 전달되며, Reservation Service에서 Redis 직접 조회로 검증한다.
     */
    @PostMapping("/hold")
    @ResponseStatus(HttpStatus.CREATED)
    fun holdSeats(
        @RequestHeader(HEADER_USER_ID) userId: UUID,
        @RequestHeader(HEADER_QUEUE_TOKEN) queueToken: String,
        @RequestBody @Valid request: HoldRequest
    ): HoldResponse {
        log.info { "Request to hold seat $request" }
        return reservationService.holdSeats(userId, request, queueToken)
    }

    /**
     * 선점 좌석 변경 (REQ-RSV-002)
     *
     * PENDING 상태 예매의 좌석을 교체한다.
     */
    @PutMapping("/hold/{reservationId}")
    fun changeSeats(
        @RequestHeader(HEADER_USER_ID) userId: UUID,
        @RequestHeader(HEADER_QUEUE_TOKEN) queueToken: String,
        @PathVariable reservationId: UUID,
        @RequestBody @Valid request: ChangeSeatsRequest
    ): ChangeSeatsResponse {
        return reservationService.changeSeats(userId, reservationId, request, queueToken)
    }

    /**
     * 예매 취소 (REQ-RSV-006, REQ-RSV-011)
     *
     * PENDING/CONFIRMED 상태 예매를 취소한다.
     * Queue Token 불필요 — 취소는 대기열 토큰 만료 후에도 가능.
     * CONFIRMED 취소 시 ReservationCancelled 이벤트를 Outbox에 저장하며,
     * Payment Service가 해당 이벤트를 소비하여 환불을 처리한다.
     */
    @DeleteMapping("/{reservationId}")
    fun cancelReservation(
        @RequestHeader(HEADER_USER_ID) userId: UUID,
        @PathVariable reservationId: UUID
    ): CancelResponse {
        return reservationService.cancelReservation(userId, reservationId)
    }
}
