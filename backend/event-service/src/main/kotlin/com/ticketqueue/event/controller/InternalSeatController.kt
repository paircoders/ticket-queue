package com.ticketqueue.event.controller

import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.service.SeatService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 내부 서비스 간 통신용 좌석 API 컨트롤러
// 엔드포인트:
//   GET /internal/seats/status/{scheduleId}       - SOLD 좌석 ID 조회 (Reservation Service 전용)
//   GET /internal/seats/{scheduleId}/details      - 좌석 상세 정보 조회 (Reservation Service 선점 시 스냅샷용)
// 보안: InternalApiAuthInterceptor가 X-Service-Api-Key 헤더 검증 (API Gateway는 /internal 경로 차단)
@RestController
@RequestMapping("/internal/seats")
class InternalSeatController(
    private val seatService: SeatService
) {

    /** SOLD 좌석 ID 목록 조회 - Reservation Service가 좌석 상태 확인 시 사용 */
    @GetMapping("/status/{scheduleId}")
    fun getSoldSeatIds(@PathVariable scheduleId: UUID): SeatDto.SoldSeatsResponse {
        return seatService.getSoldSeatIds(scheduleId)
    }

    /** 좌석 상세 정보 조회 - Reservation Service가 선점 시 스냅샷 저장용으로 사용 */
    @GetMapping("/{scheduleId}/details")
    fun getSeatDetails(
        @PathVariable scheduleId: UUID,
        @RequestParam seatIds: List<UUID>
    ): SeatDto.SeatDetailsResponse {
        return seatService.getSeatDetails(scheduleId, seatIds)
    }
}
