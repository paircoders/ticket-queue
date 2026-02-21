package com.ticketqueue.event.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.dto.VenueDto
import com.ticketqueue.event.entity.Venue
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.HallRepository
import com.ticketqueue.event.repository.VenueRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 공연장(Venue) 관리 서비스
 *
 * 공연장의 CRUD 기능을 제공한다.
 * 삭제 시에는 데이터 무결성을 위해 연관된 이벤트 및 홀의 존재 여부를 검증한다.
 * 기본적으로 읽기 전용 트랜잭션으로 동작하며, 쓰기 작업은 개별 메서드에서 트랜잭션을 오버라이드한다.
 */
@Service
@Transactional(readOnly = true)
class VenueService(
    private val venueRepository: VenueRepository,
    private val hallRepository: HallRepository,
    private val eventRepository: EventRepository
) {

    /**
     * 공연장 생성
     *
     * @param request 공연장 생성 요청 (이름, 주소, 도시)
     * @return 생성된 공연장 정보
     */
    @Transactional
    fun createVenue(request: VenueDto.CreateRequest): VenueDto.Response {
        val venue = Venue(
            name = request.name,
            address = request.address,
            city = request.city
        )
        val saved = venueRepository.save(venue)
        return VenueDto.Response.from(saved)
    }

    /**
     * 공연장 목록 조회 (페이징)
     *
     * 도시(city) 파라미터가 있으면 해당 도시로 필터링하고,
     * 없으면 전체 공연장을 조회한다.
     *
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     * @param city 도시 필터 (null이면 전체 조회)
     * @return 페이징된 공연장 목록
     */
    fun getVenues(page: Int, size: Int, city: String?): Page<VenueDto.Response> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 100)
        val pageable = PageRequest.of(safePage, safeSize)
        val venues = if (city != null) {
            venueRepository.findByCity(city, pageable)
        } else {
            venueRepository.findAll(pageable)
        }
        return venues.map { VenueDto.Response.from(it) }
    }

    /**
     * 공연장 상세 조회
     *
     * QueryDSL LEFT JOIN FETCH로 Venue와 Hall을 단일 쿼리로 조회하여 N+1 문제를 해결한다.
     *
     * @param venueId 조회할 공연장 ID
     * @return 공연장 상세 정보 (홀 목록 포함)
     * @throws EventException 공연장이 존재하지 않을 경우 (VENUE_NOT_FOUND)
     */
    fun getVenue(venueId: UUID): VenueDto.DetailResponse {
        val venue = venueRepository.findVenueWithHalls(venueId)
            ?: throw EventException(ErrorCode.VENUE_NOT_FOUND)
        return VenueDto.DetailResponse.from(venue, venue.halls)
    }

    /**
     * 공연장 부분 수정 (PATCH)
     *
     * 요청에 포함된 필드만 업데이트하며, null 필드는 기존 값을 유지한다.
     * JPA 더티 체킹을 통해 트랜잭션 종료 시 자동으로 UPDATE 쿼리가 실행된다.
     *
     * @param venueId 수정할 공연장 ID
     * @param request 수정할 필드 (null인 필드는 변경하지 않음)
     * @return 수정된 공연장 정보
     * @throws EventException 공연장이 존재하지 않을 경우 (VENUE_NOT_FOUND)
     */
    @Transactional
    fun updateVenue(venueId: UUID, request: VenueDto.UpdateRequest): VenueDto.UpdateResponse {
        val venue = findVenueById(venueId)
        venue.update(
            name = request.name,
            address = request.address,
            city = request.city
        )
        return VenueDto.UpdateResponse.from(venue)
    }

    /**
     * 공연장 삭제
     *
     * 삭제 전 데이터 무결성을 보장하기 위해 두 단계의 검증을 수행한다:
     * 1. 이벤트 존재 여부 확인 - 공연이 등록된 공연장은 삭제 불가
     * 2. 홀 존재 여부 확인 - 홀이 남아있는 공연장은 삭제 불가
     *
     * 이벤트 검증을 먼저 수행하여, 사용자에게 더 구체적인 오류 메시지를 제공한다.
     *
     * @param venueId 삭제할 공연장 ID
     * @return 삭제 완료 메시지
     * @throws EventException 공연장이 존재하지 않거나 (VENUE_NOT_FOUND),
     *         연관된 이벤트가 있거나 (VENUE_HAS_EVENTS),
     *         연관된 홀이 있을 경우 (VENUE_HAS_HALLS)
     */
    @Transactional
    fun deleteVenue(venueId: UUID): VenueDto.DeleteResponse {
        val venue = findVenueById(venueId)

        // 1. 이벤트 존재 여부 확인 (공연이 있으면 삭제 불가)
        if (eventRepository.existsByVenueId(venueId)) {
            throw EventException(ErrorCode.VENUE_HAS_EVENTS)
        }

        // 2. 홀 존재 여부 확인 (홀이 남아있으면 삭제 불가)
        if (hallRepository.existsByVenueId(venueId)) {
            throw EventException(ErrorCode.VENUE_HAS_HALLS)
        }

        venueRepository.delete(venue)
        return VenueDto.DeleteResponse(message = "공연장이 삭제되었습니다.")
    }

    private fun findVenueById(venueId: UUID): Venue {
        return venueRepository.findById(venueId)
            .orElseThrow { EventException(ErrorCode.VENUE_NOT_FOUND) }
    }
}
