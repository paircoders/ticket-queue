package com.ticketqueue.event.controller

import com.ticketqueue.common.dto.PageResponse
import com.ticketqueue.event.dto.VenueDto
import com.ticketqueue.event.service.VenueService
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
 * 공연장(Venue) 관리 REST API 컨트롤러
 *
 * 엔드포인트 목록:
 * - POST   /venues           : 공연장 생성 (ADMIN 전용) - REQ-EVT-007
 * - GET    /venues           : 공연장 목록 조회 (공개) - REQ-EVT-008
 * - GET    /venues/{venueId} : 공연장 상세 조회 (공개) - REQ-EVT-009
 * - PATCH  /venues/{venueId} : 공연장 수정 (ADMIN 전용) - REQ-EVT-010
 * - DELETE /venues/{venueId} : 공연장 삭제 (ADMIN 전용) - REQ-EVT-011
 *
 * GET 요청은 인증 없이 접근 가능하며, 변경 작업(POST/PATCH/DELETE)은 ADMIN 권한이 필요하다.
 * 권한 규칙은 SecurityConfig에서 정의된다.
 *
 * @see SecurityConfig
 */
@RestController
@RequestMapping("/venues")
class VenueController(
    private val venueService: VenueService
) {

    /** 공연장 생성 (REQ-EVT-007) - ADMIN 권한 필요 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createVenue(@Valid @RequestBody request: VenueDto.CreateRequest): VenueDto.Response {
        return venueService.createVenue(request)
    }

    /** 공연장 목록 조회 (REQ-EVT-008) - 공개 API, 도시 필터링 및 페이징 지원 */
    @GetMapping
    fun getVenues(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) city: String?
    ): PageResponse<VenueDto.Response> {
        val result = venueService.getVenues(page, size, city)
        return PageResponse(
            list = result.content,
            page = result.number,
            size = result.size,
            totalElements = result.totalElements
        )
    }

    /** 공연장 상세 조회 (REQ-EVT-009) - 공개 API, 소속 홀 목록 포함 */
    @GetMapping("/{venueId}")
    fun getVenue(@PathVariable venueId: UUID): VenueDto.DetailResponse {
        return venueService.getVenue(venueId)
    }

    /** 공연장 부분 수정 (REQ-EVT-010) - ADMIN 권한 필요, PATCH로 null 필드는 무시 */
    @PatchMapping("/{venueId}")
    fun updateVenue(
        @PathVariable venueId: UUID,
        @Valid @RequestBody request: VenueDto.UpdateRequest
    ): VenueDto.UpdateResponse {
        return venueService.updateVenue(venueId, request)
    }

    /** 공연장 삭제 (REQ-EVT-011) - ADMIN 권한 필요, 연관 이벤트/홀 존재 시 삭제 불가 */
    @DeleteMapping("/{venueId}")
    fun deleteVenue(@PathVariable venueId: UUID): VenueDto.DeleteResponse {
        return venueService.deleteVenue(venueId)
    }
}
