package com.ticketqueue.event.controller

import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.service.ScheduleService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 공연 회차(EventSchedule) 관리 REST API 컨트롤러
 *
 * 엔드포인트 목록:
 * - POST   /events/{eventId}/schedules            : 회차 생성 (ADMIN 전용) - REQ-EVT-001
 * - GET    /events/{eventId}/schedules            : 회차 목록 조회 (공개) - REQ-EVT-007
 * - GET    /events/schedules/{scheduleId}         : 회차 상세 조회 (공개) - REQ-EVT-007
 * - PATCH  /events/schedules/{scheduleId}/status : 회차 상태 변경 (ADMIN 전용) - REQ-EVT-007
 *
 * 경로가 /events/{eventId}/schedules 와 /events/schedules/{scheduleId}로 혼합되어 있어
 * 클래스 레벨 @RequestMapping 대신 메서드별 전체 경로를 정의한다.
 * 권한 규칙은 SecurityConfig의 /events/ 와일드카드 규칙이 모든 엔드포인트를 커버한다.
 */
@RestController
class ScheduleController(
    private val scheduleService: ScheduleService
) {

    /** 회차 생성 (REQ-EVT-001) - ADMIN 권한 필요 */
    @PostMapping("/events/{eventId}/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    fun createSchedule(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: ScheduleDto.CreateRequest
    ): ScheduleDto.CreateResponse {
        return scheduleService.createSchedule(eventId, request)
    }

    /** 회차 목록 조회 (REQ-EVT-007) - 공개 API */
    @GetMapping("/events/{eventId}/schedules")
    fun getSchedules(@PathVariable eventId: UUID): List<ScheduleDto.ListResponse> {
        return scheduleService.getSchedules(eventId)
    }

    /** 회차 상세 조회 (REQ-EVT-007) - 공개 API */
    @GetMapping("/events/schedules/{scheduleId}")
    fun getSchedule(@PathVariable scheduleId: UUID): ScheduleDto.DetailResponse {
        return scheduleService.getSchedule(scheduleId)
    }

    /** 회차 상태 변경 (REQ-EVT-007) - ADMIN 권한 필요 */
    @PatchMapping("/events/schedules/{scheduleId}/status")
    fun changeScheduleStatus(
        @PathVariable scheduleId: UUID,
        @Valid @RequestBody request: ScheduleDto.ChangeStatusRequest
    ): ScheduleDto.ChangeStatusResponse {
        return scheduleService.changeScheduleStatus(scheduleId, request)
    }
}
