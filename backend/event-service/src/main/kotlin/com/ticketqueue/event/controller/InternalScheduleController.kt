package com.ticketqueue.event.controller

import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.service.ScheduleService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 내부 서비스 간 통신용 회차 API 컨트롤러
// 엔드포인트: GET /internal/schedules/{scheduleId}/sellable - 티켓 판매 가능 여부 조회 (Queue Service 전용)
// 보안: InternalApiAuthInterceptor가 X-Service-Api-Key 헤더 검증 (API Gateway는 /internal 경로 차단)
@RestController
@RequestMapping("/internal/schedules")
class InternalScheduleController(
    private val scheduleService: ScheduleService
) {

    /** 티켓 판매 가능 여부 조회 - Queue Service가 대기열 진입 전 회차 유효성 검증 시 사용 */
    @GetMapping("/{scheduleId}/sellable")
    fun checkSellable(@PathVariable scheduleId: UUID): ScheduleDto.SellableResponse {
        return scheduleService.checkSellable(scheduleId)
    }

    /** 종료/취소 후 24시간 경과한 회차 ID 목록 조회 — Queue Service 정리 배치에서 사용 */
    @GetMapping("/ended")
    fun getEndedScheduleIds(): ScheduleDto.EndedScheduleIdsResponse {
        return ScheduleDto.EndedScheduleIdsResponse(scheduleService.getCleanupTargetScheduleIds())
    }
}
