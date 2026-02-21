package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.dto.HallDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.HallRepository
import com.ticketqueue.event.repository.VenueRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 홀(Hall) 관리 서비스
 *
 * 공연장 내 홀의 CRUD 기능을 제공한다.
 * 좌석 템플릿(SeatTemplateDto)은 DTO 형태로 수신하여 검증 후 JSON 문자열로 직렬화하여 저장하고,
 * 조회 시에는 JSON 문자열을 다시 DTO로 역직렬화하여 반환한다.
 * ObjectMapper를 통해 SeatTemplateDto <-> JSONB 변환을 처리한다.
 */
@Service
@Transactional(readOnly = true)
class HallService(
    private val hallRepository: HallRepository,
    private val venueRepository: VenueRepository,
    private val eventRepository: EventRepository,
    private val objectMapper: ObjectMapper
) {

    /**
     * 홀 생성
     *
     * 1. 공연장 존재 여부 확인
     * 2. 동일 공연장 내 홀 이름 중복 검증
     * 3. 좌석 템플릿 비즈니스 검증 (행-등급 매핑 일관성)
     * 4. SeatTemplateDto를 JSON 문자열로 직렬화하여 JSONB 컬럼에 저장
     *
     * @param venueId 홀이 속할 공연장 ID
     * @param request 홀 생성 요청 (이름, 수용 인원, 좌석 템플릿)
     * @return 생성된 홀 정보
     * @throws EventException 공연장이 존재하지 않거나 (VENUE_NOT_FOUND),
     *         동일 이름의 홀이 이미 존재하거나 (HALL_NAME_DUPLICATE),
     *         좌석 템플릿 매핑이 올바르지 않을 경우 (INVALID_SEAT_TEMPLATE_MAPPING)
     */
    @Transactional
    fun createHall(venueId: UUID, request: HallDto.CreateRequest): HallDto.Response {
        val venue = venueRepository.findById(venueId)
            .orElseThrow { EventException(ErrorCode.VENUE_NOT_FOUND) }

        // 동일 공연장 내 홀 이름 중복 검증
        if (hallRepository.existsByVenueIdAndName(venueId, request.name)) {
            throw EventException(ErrorCode.HALL_NAME_DUPLICATE)
        }

        // 좌석 템플릿 비즈니스 검증
        validateSeatTemplate(request.seatTemplate)

        // SeatTemplateDto -> JSON 문자열로 직렬화하여 JSONB 컬럼에 저장
        val seatTemplateJson = objectMapper.writeValueAsString(request.seatTemplate)
        val hall = Hall(
            venue = venue,
            name = request.name,
            capacity = request.capacity,
            seatTemplate = seatTemplateJson
        )
        val saved = hallRepository.save(hall)
        return HallDto.Response.from(saved)
    }

    /**
     * 특정 공연장의 홀 목록 조회
     *
     * @param venueId 공연장 ID
     * @return 해당 공연장에 속한 홀 목록
     * @throws EventException 공연장이 존재하지 않을 경우 (VENUE_NOT_FOUND)
     */
    fun getHalls(venueId: UUID): List<HallDto.Response> {
        if (!venueRepository.existsById(venueId)) {
            throw EventException(ErrorCode.VENUE_NOT_FOUND)
        }
        return hallRepository.findByVenueId(venueId)
            .map { HallDto.Response.from(it) }
    }

    /**
     * 홀 상세 조회
     *
     * DB에 JSONB로 저장된 좌석 템플릿을 SeatTemplateDto로 역직렬화하여 응답에 포함한다.
     *
     * @param venueId 공연장 ID
     * @param hallId 홀 ID
     * @return 홀 상세 정보 (좌석 템플릿 포함)
     * @throws EventException 홀이 존재하지 않거나 (HALL_NOT_FOUND),
     *         좌석 템플릿 데이터가 손상된 경우 (INVALID_SEAT_TEMPLATE)
     */
    fun getHall(venueId: UUID, hallId: UUID): HallDto.DetailResponse {
        val hall = findHallByVenueIdAndId(venueId, hallId)
        // JSONB 문자열 -> SeatTemplateDto 역직렬화 (에러 핸들링)
        val seatTemplate = try {
            objectMapper.readValue(hall.seatTemplate, SeatTemplateDto::class.java)
        } catch (e: JsonProcessingException) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE)
        }
        return HallDto.DetailResponse.from(hall, seatTemplate)
    }

    /**
     * 홀 부분 수정 (PATCH)
     *
     * 1. 이름 변경 요청 시에만 중복 검증 수행 (기존 이름과 다를 때만)
     *    - 자기 자신을 제외한 동일 공연장 내 이름 중복 체크 (idNot 조건)
     * 2. seatTemplate이 요청에 포함된 경우 비즈니스 검증 후 JSON 직렬화 수행
     * 3. null 필드는 엔티티의 update()에서 무시됨 (기존 값 유지)
     *
     * @param venueId 공연장 ID
     * @param hallId 수정할 홀 ID
     * @param request 수정할 필드 (null인 필드는 변경하지 않음)
     * @return 수정된 홀 정보
     * @throws EventException 홀이 존재하지 않거나 (HALL_NOT_FOUND),
     *         동일 이름의 홀이 이미 존재하거나 (HALL_NAME_DUPLICATE),
     *         좌석 템플릿 매핑이 올바르지 않을 경우 (INVALID_SEAT_TEMPLATE_MAPPING)
     */
    @Transactional
    fun updateHall(venueId: UUID, hallId: UUID, request: HallDto.UpdateRequest): HallDto.UpdateResponse {
        val hall = findHallByVenueIdAndId(venueId, hallId)

        // 이름이 실제로 변경될 때만 중복 검증 (자기 자신 제외)
        if (request.name != null && request.name != hall.name) {
            if (hallRepository.existsByVenueIdAndNameExcluding(venueId, request.name, hallId)) {
                throw EventException(ErrorCode.HALL_NAME_DUPLICATE)
            }
        }

        // seatTemplate이 요청에 포함된 경우 비즈니스 검증 및 JSON 직렬화
        val seatTemplateJson = request.seatTemplate?.let {
            validateSeatTemplate(it)
            objectMapper.writeValueAsString(it)
        }
        hall.update(
            name = request.name,
            capacity = request.capacity,
            seatTemplate = seatTemplateJson
        )
        return HallDto.UpdateResponse.from(hall)
    }

    /**
     * 홀 삭제
     *
     * 삭제 전 해당 홀에 등록된 이벤트가 있는지 확인하여 데이터 무결성을 보장한다.
     *
     * @param venueId 공연장 ID
     * @param hallId 삭제할 홀 ID
     * @return 삭제 완료 메시지
     * @throws EventException 홀이 존재하지 않거나 (HALL_NOT_FOUND),
     *         연관된 이벤트가 있을 경우 (HALL_HAS_EVENTS)
     */
    @Transactional
    fun deleteHall(venueId: UUID, hallId: UUID): HallDto.DeleteResponse {
        val hall = findHallByVenueIdAndId(venueId, hallId)

        // 이벤트가 등록된 홀은 삭제 불가
        if (eventRepository.existsByHallId(hallId)) {
            throw EventException(ErrorCode.HALL_HAS_EVENTS)
        }

        hallRepository.delete(hall)
        return HallDto.DeleteResponse(message = "홀이 삭제되었습니다.")
    }

    private fun findHallByVenueIdAndId(venueId: UUID, hallId: UUID): Hall {
        return hallRepository.findByVenueIdAndId(venueId, hallId)
            ?: throw EventException(ErrorCode.HALL_NOT_FOUND)
    }

    /**
     * 좌석 템플릿 비즈니스 검증
     *
     * 1. 모든 행(rows)이 gradeMapping에 매핑되어 있는지 확인
     * 2. 중복 행 이름 검증
     *
     * @param seatTemplate 검증할 좌석 템플릿
     * @throws EventException 좌석 템플릿 매핑이 올바르지 않을 경우 (INVALID_SEAT_TEMPLATE_MAPPING)
     */
    private fun validateSeatTemplate(seatTemplate: SeatTemplateDto) {
        // 중복 행 이름 검증
        val uniqueRows = seatTemplate.rows.toSet()
        if (uniqueRows.size != seatTemplate.rows.size) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING)
        }

        // rows와 gradeMapping 키가 완전히 일치하는지 양방향 검증
        // 정방향: 매핑 누락 행 검출 / 역방향: 잉여 매핑 키 검출
        if (uniqueRows != seatTemplate.gradeMapping.keys) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING)
        }
    }
}
