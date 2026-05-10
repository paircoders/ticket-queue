package com.ticketqueue.reservation.controller

import com.ticketqueue.reservation.dto.ReservationDto.ChangeSeatsRequest
import com.ticketqueue.reservation.dto.ReservationDto.ChangeSeatsResponse
import com.ticketqueue.reservation.dto.ReservationDto.HoldRequest
import com.ticketqueue.reservation.dto.ReservationDto.HoldResponse
import com.ticketqueue.reservation.dto.ReservationDto.SeatStatusResponse
import com.ticketqueue.reservation.service.ReservationService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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
}
