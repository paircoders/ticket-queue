package com.ticketqueue.event.controller

import com.ticketqueue.common.dto.PageResponse
import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.entity.EventStatus
import com.ticketqueue.event.service.EventService
import com.ticketqueue.event.service.SeatService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 공연(Event) 관리 REST API 컨트롤러
 *
 * 엔드포인트 목록:
 * - POST   /events           : 공연 생성 (ADMIN 전용) - REQ-EVT-001
 * - GET    /events           : 공연 목록 조회 (공개, 페이징/필터/검색) - REQ-EVT-004
 * - GET    /events/{eventId} : 공연 상세 조회 (공개, 회차 날짜별 그룹핑) - REQ-EVT-005
 * - PATCH  /events/{eventId} : 공연 수정 (ADMIN 전용, 판매 전/후 차등) - REQ-EVT-002
 * - DELETE /events/{eventId} : 공연 Soft Delete (ADMIN 전용, SOLD 좌석 있으면 불가) - REQ-EVT-003
 *
 * GET 요청은 인증 없이 접근 가능하며, 변경 작업(POST/PATCH/DELETE)은 ADMIN 권한이 필요하다.
 * 권한 규칙은 SecurityConfig에서 정의된다.
 */
@RestController
@RequestMapping("/events")
class EventController(
    private val eventService: EventService,
    private val seatService: SeatService
) {

    /** 공연 생성 (REQ-EVT-001) - ADMIN 권한 필요 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createEvent(@Valid @RequestBody request: EventDto.CreateRequest): EventDto.CreateResponse {
        return eventService.createEvent(request)
    }

    /** 공연 목록 조회 (REQ-EVT-004) - 공개 API, 상태/도시/검색어 필터링 및 페이징 */
    @GetMapping
    fun getEvents(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: EventStatus?,
        @RequestParam(required = false) city: String?,
        @RequestParam(required = false) keyword: String?
    ): PageResponse<EventDto.ListResponse> {
        val result = eventService.getEvents(page, size, status, city, keyword)
        return PageResponse(
            list = result.content,
            page = result.number,
            size = result.size,
            totalElements = result.totalElements
        )
    }

    /** 공연 상세 조회 (REQ-EVT-005) - 공개 API, 회차 날짜별 그룹핑 + isSoldOut */
    @GetMapping("/{eventId}")
    fun getEvent(@PathVariable eventId: UUID): EventDto.DetailResponse {
        return eventService.getEvent(eventId)
    }

    /** 회차별 좌석 정보 조회 (REQ-EVT-006) - 공개 API, 등급별 그룹핑 + Redis 캐싱 */
    @GetMapping("/schedules/{scheduleId}/seats")
    fun getSeats(@PathVariable scheduleId: UUID): SeatDto.SeatsResponse {
        return seatService.getSeats(scheduleId)
    }

    /** 공연 수정 (REQ-EVT-002) - ADMIN 권한 필요, 판매 후 artist 변경 불가 */
    @PatchMapping("/{eventId}")
    fun updateEvent(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: EventDto.UpdateRequest
    ): EventDto.UpdateResponse {
        return eventService.updateEvent(eventId, request)
    }

    /** 공연 삭제 (REQ-EVT-003) - ADMIN 권한 필요, SOLD 좌석 있으면 409 */
    @DeleteMapping("/{eventId}")
    fun deleteEvent(@PathVariable eventId: UUID): EventDto.DeleteResponse {
        return eventService.deleteEvent(eventId)
    }
}
