package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.config.CacheProperties
import com.ticketqueue.event.dto.HallDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.HallRepository
import com.ticketqueue.event.repository.VenueRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

/**
 * 홀(Hall) 관리 서비스
 *
 * 공연장 내 홀의 CRUD 기능을 제공한다.
 * 좌석 템플릿(SeatTemplateDto)은 DTO 형태로 수신하여 검증 후 JSON 문자열로 직렬화하여 저장하고,
 * 조회 시에는 JSON 문자열을 다시 DTO로 역직렬화하여 반환한다.
 * ObjectMapper를 통해 SeatTemplateDto <-> JSONB 변환을 처리한다.
 *
 * Cache-Aside 전략 (REQ-EVT-017):
 * - 홀 상세(좌석 배치도): `cache:layout:{hallId}` (String JSON, TTL 24시간)
 * - 홀 구조는 변경 빈도가 매우 낮으므로 Stampede Lock 미적용
 * - 캐시 무효화: 수정/삭제 시 해당 홀 캐시 제거 (REQ-EVT-019)
 */
@Service
@Transactional(readOnly = true)
class HallService(
    private val hallRepository: HallRepository,
    private val venueRepository: VenueRepository,
    private val eventRepository: EventRepository,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val cacheProperties: CacheProperties
) {

    private val log = LoggerFactory.getLogger(HallService::class.java)

    private companion object {
        const val LAYOUT_CACHE_PREFIX = "cache:layout:"
    }

    /**
     * 홀 생성
     *
     * 1. 공연장 존재 여부 확인
     * 2. 동일 공연장 내 홀 이름 중복 검증
     * 3. 좌석 템플릿 비즈니스 검증 (행-등급 매핑 일관성)
     * 4. SeatTemplateDto를 JSON 문자열로 직렬화하여 JSONB 컬럼에 저장
     */
    @Transactional
    fun createHall(venueId: UUID, request: HallDto.CreateRequest): HallDto.Response {
        val venue = venueRepository.findById(venueId)
            .orElseThrow { EventException(ErrorCode.VENUE_NOT_FOUND) }

        if (hallRepository.existsByVenueIdAndName(venueId, request.name)) {
            throw EventException(ErrorCode.HALL_NAME_DUPLICATE)
        }

        validateSeatTemplate(request.seatTemplate)

        val seatTemplateJson = objectMapper.writeValueAsString(request.seatTemplate)
        val hall = Hall(
            venue = venue,
            name = request.name,
            capacity = request.capacity,
            seatTemplate = seatTemplateJson
        )
        val saved = hallRepository.saveAndFlush(hall)
        return HallDto.Response.from(saved)
    }

    /**
     * 특정 공연장의 홀 목록 조회
     */
    fun getHalls(venueId: UUID): List<HallDto.Response> {
        if (!venueRepository.existsById(venueId)) {
            throw EventException(ErrorCode.VENUE_NOT_FOUND)
        }
        return hallRepository.findByVenueId(venueId)
            .map { HallDto.Response.from(it) }
    }

    /**
     * 홀 상세 조회 (좌석 배치도 포함) - Cache-Aside 적용
     *
     * 홀 구조는 변경 빈도가 낮으므로 TTL 24시간을 적용하며,
     * 관리자 전용 저빈도 API이므로 Stampede Lock은 적용하지 않는다.
     *
     * Cache-Aside 전략:
     * 1. String JSON 캐시 조회 → hit 시 즉시 반환
     * 2. miss 시 → DB 조회 → 캐시 저장
     */
    fun getHall(venueId: UUID, hallId: UUID): HallDto.DetailResponse {
        val cacheKey = "$LAYOUT_CACHE_PREFIX$hallId"

        // 1. Cache read
        try {
            val cached = redisTemplate.opsForValue().get(cacheKey) as? String
            if (cached != null) {
                return objectMapper.readValue(cached, HallDto.DetailResponse::class.java)
            }
        } catch (e: DataAccessException) {
            log.warn("Redis cache read failed for key: $cacheKey", e)
        } catch (e: JsonProcessingException) {
            log.warn("Redis cache deserialization failed for key: $cacheKey, skipping cache", e)
            try { redisTemplate.delete(cacheKey) } catch (ignored: DataAccessException) {}
        }

        // 2. DB query
        val hall = findHallByVenueIdAndId(venueId, hallId)
        val seatTemplate = try {
            objectMapper.readValue(hall.seatTemplate, SeatTemplateDto::class.java)
        } catch (e: JsonProcessingException) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE, cause = e)
        }
        val detailResponse = HallDto.DetailResponse.from(hall, seatTemplate)

        // 3. Cache write
        try {
            val json = objectMapper.writeValueAsString(detailResponse)
            redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(cacheProperties.layout.ttl))
        } catch (e: DataAccessException) {
            log.warn("Redis cache write failed for key: $cacheKey", e)
        } catch (e: JsonProcessingException) {
            log.warn("Redis cache serialization failed for key: $cacheKey", e)
        }

        return detailResponse
    }

    /**
     * 홀 부분 수정 (PATCH)
     *
     * 수정 성공 후 해당 홀의 좌석 배치도 캐시를 무효화한다.
     */
    @Transactional
    fun updateHall(venueId: UUID, hallId: UUID, request: HallDto.UpdateRequest): HallDto.UpdateResponse {
        val hall = findHallByVenueIdAndId(venueId, hallId)

        if (request.name != null && request.name != hall.name) {
            if (hallRepository.existsByVenueIdAndNameExcluding(venueId, request.name, hallId)) {
                throw EventException(ErrorCode.HALL_NAME_DUPLICATE)
            }
        }

        val seatTemplateJson = request.seatTemplate?.let {
            validateSeatTemplate(it)
            objectMapper.writeValueAsString(it)
        }
        hall.update(
            name = request.name,
            capacity = request.capacity,
            seatTemplate = seatTemplateJson
        )

        invalidateLayoutCache(hallId)
        return HallDto.UpdateResponse.from(hall)
    }

    /**
     * 홀 삭제
     *
     * 삭제 성공 후 해당 홀의 좌석 배치도 캐시를 무효화한다.
     */
    @Transactional
    fun deleteHall(venueId: UUID, hallId: UUID): HallDto.DeleteResponse {
        val hall = findHallByVenueIdAndId(venueId, hallId)

        if (eventRepository.existsByHallId(hallId)) {
            throw EventException(ErrorCode.HALL_HAS_EVENTS)
        }

        hallRepository.delete(hall)
        invalidateLayoutCache(hallId)
        return HallDto.DeleteResponse(message = "홀이 삭제되었습니다.")
    }

    private fun findHallByVenueIdAndId(venueId: UUID, hallId: UUID): Hall {
        return hallRepository.findByVenueIdAndId(venueId, hallId)
            ?: throw EventException(ErrorCode.HALL_NOT_FOUND)
    }

    /**
     * 좌석 템플릿 비즈니스 검증
     */
    private fun validateSeatTemplate(seatTemplate: SeatTemplateDto) {
        val uniqueRows = seatTemplate.rows.toSet()
        if (uniqueRows.size != seatTemplate.rows.size) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING)
        }
        if (uniqueRows != seatTemplate.gradeMapping.keys) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING)
        }
    }

    /**
     * 홀 좌석 배치도 캐시 삭제 (REQ-EVT-019)
     */
    private fun invalidateLayoutCache(hallId: UUID) {
        try {
            redisTemplate.delete("$LAYOUT_CACHE_PREFIX$hallId")
        } catch (e: DataAccessException) {
            log.warn("Redis layout cache invalidation failed for hallId: $hallId", e)
        }
    }
}
