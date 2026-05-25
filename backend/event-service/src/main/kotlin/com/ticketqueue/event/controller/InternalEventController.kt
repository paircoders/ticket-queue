package com.ticketqueue.event.controller

import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.service.EventService
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 내부 서비스 간 통신용 공연 API 컨트롤러
// 엔드포인트:
//   GET /internal/events/{eventId}/info       - 단건 공연 메타 조회 (Reservation Service 예매 상세 등)
//   GET /internal/events/batch?eventIds=...   - 배치 공연 메타 조회 (최대 100, 예매 내역 페이지 일괄 조립)
// 보안: InternalApiAuthInterceptor가 X-Service-Api-Key 헤더 검증 (API Gateway는 /internal 경로 차단)
@RestController
@RequestMapping("/internal/events")
@Validated
class InternalEventController(
    private val eventService: EventService
) {

    /** 공연 핵심 정보 단건 조회 — 미존재 시 EVENT_NOT_FOUND (404) */
    @GetMapping("/{eventId}/info")
    fun getEventInfo(@PathVariable eventId: UUID): EventDto.EventInfoResponse {
        return eventService.getEventInfo(eventId)
    }

    /**
     * 공연 핵심 정보 배치 조회 — 미존재 ID는 응답에서 제외
     *
     * 최대 100건까지 한 번에 조회 가능. 0건 또는 100건 초과 시 400 INVALID_INPUT.
     */
    @GetMapping("/batch")
    fun getEventInfoBatch(
        @RequestParam @Size(min = 1, max = 100, message = "eventIds는 1~100개여야 합니다.") eventIds: List<UUID>
    ): EventDto.EventInfoBatchResponse {
        return eventService.getEventInfoBatch(eventIds)
    }
}
