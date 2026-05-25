package com.ticketqueue.event.controller

import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.service.ScheduleService
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 내부 서비스 간 통신용 회차 API 컨트롤러
// 엔드포인트:
//   GET /internal/schedules/{scheduleId}/sellable    - 티켓 판매 가능 여부 조회 (Queue Service 전용)
//   GET /internal/schedules/ended                    - 종료된 회차 ID 목록 (Queue Service 정리 배치)
//   GET /internal/schedules/{scheduleId}/info        - 회차 핵심 정보 단건 조회
//   GET /internal/schedules/batch?scheduleIds=...    - 회차 핵심 정보 배치 조회 (최대 100, Reservation Service)
// 보안: InternalApiAuthInterceptor가 X-Service-Api-Key 헤더 검증 (API Gateway는 /internal 경로 차단)
@RestController
@RequestMapping("/internal/schedules")
@Validated
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

    /** 회차 핵심 정보 조회 — 취소 검증, 환불 기준일 등 내부 서비스 공용 */
    @GetMapping("/{scheduleId}/info")
    fun getScheduleInfo(@PathVariable scheduleId: UUID): ScheduleDto.ScheduleInfoResponse {
        return scheduleService.getScheduleInfo(scheduleId)
    }

    /**
     * 회차 핵심 정보 배치 조회 — Reservation Service 예매 내역 페이지당 일괄 조립용
     *
     * 미존재 ID는 응답에서 제외. 최대 100건 (0건 또는 100건 초과 시 400 INVALID_INPUT).
     */
    @GetMapping("/batch")
    fun getScheduleInfoBatch(
        @RequestParam @Size(min = 1, max = 100, message = "scheduleIds는 1~100개여야 합니다.") scheduleIds: List<UUID>
    ): ScheduleDto.ScheduleInfoBatchResponse {
        return scheduleService.getScheduleInfoBatch(scheduleIds)
    }
}
