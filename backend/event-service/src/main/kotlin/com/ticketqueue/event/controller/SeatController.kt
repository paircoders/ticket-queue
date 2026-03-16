package com.ticketqueue.event.controller

import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.service.SeatService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/events/schedules")
class SeatController(
    private val seatService: SeatService
) {
    /** 회차별 좌석 목록 조회 - 등급별 그룹핑, HOLD 상태 실시간 반영 (REQ-EVT-006) */
    @GetMapping("/{scheduleId}/seats")
    fun getSeats(@PathVariable scheduleId: UUID): SeatDto.SeatsResponse {
        return seatService.getSeats(scheduleId)
    }
}
