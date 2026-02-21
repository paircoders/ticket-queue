package com.ticketqueue.event.controller

import com.ticketqueue.event.dto.HallDto
import com.ticketqueue.event.service.HallService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 홀(Hall) 관리 REST API 컨트롤러
 *
 * 공연장 하위 리소스로 중첩 URL 구조를 사용한다: /venues/{venueId}/halls
 *
 * 엔드포인트 목록:
 * - POST   /venues/{venueId}/halls           : 홀 생성 (ADMIN 전용) - REQ-EVT-012
 * - GET    /venues/{venueId}/halls           : 홀 목록 조회 (공개) - REQ-EVT-013
 * - GET    /venues/{venueId}/halls/{hallId}  : 홀 상세 조회 (공개) - REQ-EVT-014
 * - PATCH  /venues/{venueId}/halls/{hallId}  : 홀 수정 (ADMIN 전용) - REQ-EVT-015
 * - DELETE /venues/{venueId}/halls/{hallId}  : 홀 삭제 (ADMIN 전용) - REQ-EVT-016
 *
 * GET 요청은 인증 없이 접근 가능하며, 변경 작업은 ADMIN 권한이 필요하다.
 *
 * @see SecurityConfig
 */
@RestController
@RequestMapping("/venues/{venueId}/halls")
class HallController(
    private val hallService: HallService
) {

    /** 홀 생성 (REQ-EVT-012) - ADMIN 권한 필요, 좌석 템플릿 검증 후 JSON 저장 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createHall(
        @PathVariable venueId: UUID,
        @Valid @RequestBody request: HallDto.CreateRequest
    ): HallDto.Response {
        return hallService.createHall(venueId, request)
    }

    /** 홀 목록 조회 (REQ-EVT-013) - 공개 API */
    @GetMapping
    fun getHalls(@PathVariable venueId: UUID): List<HallDto.Response> {
        return hallService.getHalls(venueId)
    }

    /** 홀 상세 조회 (REQ-EVT-014) - 공개 API, 좌석 템플릿 포함 */
    @GetMapping("/{hallId}")
    fun getHall(
        @PathVariable venueId: UUID,
        @PathVariable hallId: UUID
    ): HallDto.DetailResponse {
        return hallService.getHall(venueId, hallId)
    }

    /** 홀 부분 수정 (REQ-EVT-015) - ADMIN 권한 필요, 이름 변경 시 중복 검증 */
    @PatchMapping("/{hallId}")
    fun updateHall(
        @PathVariable venueId: UUID,
        @PathVariable hallId: UUID,
        @Valid @RequestBody request: HallDto.UpdateRequest
    ): HallDto.UpdateResponse {
        return hallService.updateHall(venueId, hallId, request)
    }

    /** 홀 삭제 (REQ-EVT-016) - ADMIN 권한 필요, 연관 이벤트 존재 시 삭제 불가 */
    @DeleteMapping("/{hallId}")
    fun deleteHall(
        @PathVariable venueId: UUID,
        @PathVariable hallId: UUID
    ): HallDto.DeleteResponse {
        return hallService.deleteHall(venueId, hallId)
    }
}
