package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.config.CacheProperties
import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.EventStatus
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.entity.SeatStatus
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.HallRepository
import com.ticketqueue.event.repository.SeatRepository
import com.ticketqueue.event.repository.VenueRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 공연 목록/상세 캐시에서 사용하는 페이지 래퍼 DTO
 *
 * GenericJackson2JsonRedisSerializer는 LocalDateTime 처리를 위해 Spring 관리
 * ObjectMapper가 필요하므로, 직렬화는 objectMapper.writeValueAsString()을 사용한다.
 * internal로 선언하여 동일 모듈 내 테스트에서도 접근 가능하다.
 */
internal data class CachedEventList(
    val content: List<EventDto.ListResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long
)

/**
 * 공연(Event) 관리 서비스
 *
 * 공연의 CRUD 기능과 회차/좌석 일괄 생성을 처리한다.
 * - 공연 생성 시 Hall의 seatTemplate을 기반으로 회차별 좌석 자동 초기화
 * - Soft Delete: deleted_at 컬럼으로 논리 삭제, SOLD 좌석이 있으면 삭제 불가
 * - 수정 범위 차등: 판매 시작 후에는 artist 변경 불가 (REQ-EVT-002)
 *
 * Cache-Aside 전략 (REQ-EVT-017):
 * - 공연 상세: `cache:event:{eventId}` (String JSON, TTL 5분)
 * - 공연 목록: `cache:event:list:{page}:{size}:{filters}` (String JSON, TTL 5분)
 * - Cache Stampede 방지: Lua 스크립트로 원자적 락 획득 (REQ-EVT-021)
 * - 캐시 무효화: 생성/수정/삭제 시 관련 캐시 모두 제거 (REQ-EVT-019)
 * - Redis 장애 시 DB fallback으로 서비스 가용성 유지
 */
@Service
@Transactional(readOnly = true)
class EventService(
    private val eventRepository: EventRepository,
    private val eventScheduleRepository: EventScheduleRepository,
    private val seatRepository: SeatRepository,
    private val venueRepository: VenueRepository,
    private val hallRepository: HallRepository,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val cacheProperties: CacheProperties,
    private val cacheHelper: CacheHelper
) {

    private val log = LoggerFactory.getLogger(EventService::class.java)

    private companion object {
        const val EVENT_DETAIL_PREFIX = "cache:event:"
        const val EVENT_LIST_PREFIX = "cache:event:list:"
        const val EVENT_LOCK_PREFIX = "cache:lock:event:"
        const val STAMPEDE_LOCK_TTL = 10L // seconds
    }

    /**
     * 공연 생성 - 회차 및 좌석 일괄 생성 (REQ-EVT-001)
     *
     * 생성 성공 후 공연 목록 캐시를 무효화한다.
     */
    @Transactional
    fun createEvent(request: EventDto.CreateRequest): EventDto.CreateResponse {
        val venue = venueRepository.findById(request.venueId)
            .orElseThrow { EventException(ErrorCode.VENUE_NOT_FOUND) }

        val hall = hallRepository.findById(request.hallId)
            .orElseThrow { EventException(ErrorCode.HALL_NOT_FOUND) }

        if (hall.venue.id != venue.id) {
            throw EventException(ErrorCode.HALL_NOT_IN_VENUE)
        }

        val sequences = request.schedules.map { it.playSequence }
        if (sequences.size != sequences.toSet().size) {
            throw EventException(ErrorCode.DUPLICATE_PLAY_SEQUENCE)
        }

        request.schedules.forEach { s ->
            if (!s.eventStartAt.isBefore(s.eventEndAt) || !s.saleStartAt.isBefore(s.saleEndAt)) {
                throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
            }
            if (!s.saleEndAt.isBefore(s.eventStartAt)) {
                throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
            }
            if (!s.eventStartAt.isAfter(LocalDateTime.now())) {
                throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
            }
        }

        val seatTemplate = try {
            objectMapper.readValue(hall.seatTemplate, SeatTemplateDto::class.java)
        } catch (e: JsonProcessingException) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE, cause = e)
        }

        val event = eventRepository.saveAndFlush(
            Event(title = request.title, artist = request.artist, description = request.description, venue = venue, hall = hall)
        )

        request.schedules.forEach { scheduleRequest ->
            val schedule = eventScheduleRepository.save(
                EventSchedule(
                    event = event,
                    playSequence = scheduleRequest.playSequence,
                    eventStartAt = scheduleRequest.eventStartAt,
                    eventEndAt = scheduleRequest.eventEndAt,
                    saleStartAt = scheduleRequest.saleStartAt,
                    saleEndAt = scheduleRequest.saleEndAt
                )
            )
            val seats = initializeSeats(schedule, seatTemplate, request.priceByGrade)
            seatRepository.saveAll(seats)
        }

        val response = EventDto.CreateResponse.from(event)
        invalidateEventListCaches()
        return response
    }

    /**
     * 공연 목록 조회 (페이징, 필터링, 검색) - REQ-EVT-004, P95 < 200ms
     *
     * Cache-Aside 전략:
     * 1. String JSON 캐시 조회 → hit 시 즉시 반환
     * 2. miss 시 → Stampede Lock 시도 → DB 조회 → 캐시 저장
     */
    fun getEvents(
        page: Int,
        size: Int,
        status: EventStatus?,
        city: String?,
        keyword: String?
    ): Page<EventDto.ListResponse> {
        val coercedPage = page.coerceAtLeast(0)
        val coercedSize = size.coerceIn(1, 100)
        val cacheKey = buildListCacheKey(coercedPage, coercedSize, status, city, keyword)

        // 1. Cache read
        try {
            val cached = redisTemplate.opsForValue().get(cacheKey) as? String
            if (cached != null) {
                val cachedList = objectMapper.readValue(cached, CachedEventList::class.java)
                return PageImpl(
                    cachedList.content,
                    PageRequest.of(cachedList.page, cachedList.size),
                    cachedList.totalElements
                )
            }
        } catch (e: DataAccessException) {
            log.warn("Redis cache read failed for key: $cacheKey", e)
        } catch (e: JsonProcessingException) {
            log.warn("Redis cache deserialization failed for key: $cacheKey, skipping cache", e)
            try { redisTemplate.delete(cacheKey) } catch (ignored: DataAccessException) {}
        }

        // 2. Stampede Lock
        val lockKey = "${EVENT_LOCK_PREFIX}list:$cacheKey"
        val lockAcquired = cacheHelper.tryAcquireStampedeLock(lockKey, STAMPEDE_LOCK_TTL)

        // 3. DB query
        val pageable = PageRequest.of(coercedPage, coercedSize)
        val result = eventRepository.findEventList(pageable, status, city, keyword)

        // 4. Cache write
        try {
            val cachedList = CachedEventList(
                content = result.content,
                page = result.number,
                size = result.size,
                totalElements = result.totalElements
            )
            val json = objectMapper.writeValueAsString(cachedList)
            if (lockAcquired) {
                redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(cacheProperties.event.ttl))
            } else {
                // 락 미획득이지만 캐시가 비어있으면 저장 (다른 스레드가 저장 중이면 setIfAbsent가 false 반환)
                redisTemplate.opsForValue().setIfAbsent(cacheKey, json, Duration.ofSeconds(cacheProperties.event.ttl))
            }
        } catch (e: DataAccessException) {
            log.warn("Redis cache write failed for key: $cacheKey", e)
        } catch (e: JsonProcessingException) {
            log.warn("Redis cache serialization failed for key: $cacheKey", e)
        }

        return result
    }

    /**
     * 공연 상세 조회 - REQ-EVT-005, P95 < 100ms
     *
     * Cache-Aside 전략:
     * 1. String JSON 캐시 조회 → hit 시 즉시 반환
     * 2. miss 시 → Stampede Lock 시도 → DB 조회 → 캐시 저장
     */
    fun getEvent(eventId: UUID): EventDto.DetailResponse {
        val cacheKey = "$EVENT_DETAIL_PREFIX$eventId"

        // 1. Cache read
        try {
            val cached = redisTemplate.opsForValue().get(cacheKey) as? String
            if (cached != null) {
                return objectMapper.readValue(cached, EventDto.DetailResponse::class.java)
            }
        } catch (e: DataAccessException) {
            log.warn("Redis cache read failed for key: $cacheKey", e)
        } catch (e: JsonProcessingException) {
            log.warn("Redis cache deserialization failed for key: $cacheKey, skipping cache", e)
            try { redisTemplate.delete(cacheKey) } catch (ignored: DataAccessException) {}
        }

        // 2. Stampede Lock
        val lockKey = "$EVENT_LOCK_PREFIX$eventId"
        val lockAcquired = cacheHelper.tryAcquireStampedeLock(lockKey, STAMPEDE_LOCK_TTL)

        // 3. DB query
        val event = eventRepository.findEventWithVenueAndHall(eventId)
            ?: throw EventException(ErrorCode.EVENT_NOT_FOUND)

        val schedules = eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId)
        val scheduleIds = schedules.map { it.id!! }
        val availableScheduleIds = eventRepository.findScheduleIdsWithAvailableSeats(scheduleIds)

        val scheduleDateGroups = schedules
            .map { schedule ->
                EventDto.ScheduleTimeInfo.from(schedule, isSoldOut = schedule.id!! !in availableScheduleIds)
            }
            .groupBy { it.eventStartAt.toLocalDate() }
            .map { (date, times) ->
                EventDto.ScheduleDateGroup(date = date, times = times.sortedBy { it.eventStartAt })
            }
            .sortedBy { it.date }

        val detailResponse = EventDto.DetailResponse(
            id = event.id!!,
            title = event.title,
            artist = event.artist,
            description = event.description,
            venueId = event.venue.id!!,
            venueName = event.venue.name,
            hallId = event.hall.id!!,
            hallName = event.hall.name,
            status = event.status,
            schedules = scheduleDateGroups,
            createdAt = event.createdAt!!,
            updatedAt = event.updatedAt!!
        )

        // 4. Cache write
        try {
            val json = objectMapper.writeValueAsString(detailResponse)
            if (lockAcquired) {
                redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(cacheProperties.event.ttl))
            } else {
                // 락 미획득이지만 캐시가 비어있으면 저장 (다른 스레드가 저장 중이면 setIfAbsent가 false 반환)
                redisTemplate.opsForValue().setIfAbsent(cacheKey, json, Duration.ofSeconds(cacheProperties.event.ttl))
            }
        } catch (e: DataAccessException) {
            log.warn("Redis cache write failed for key: $cacheKey", e)
        } catch (e: JsonProcessingException) {
            log.warn("Redis cache serialization failed for key: $cacheKey", e)
        }

        return detailResponse
    }

    /**
     * 공연 수정 (PATCH) - REQ-EVT-002
     *
     * 수정 성공 후 공연 상세 및 목록 캐시를 무효화한다.
     */
    @Transactional
    fun updateEvent(eventId: UUID, request: EventDto.UpdateRequest): EventDto.UpdateResponse {
        val event = findActiveEvent(eventId)
        val hasSaleStarted = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(
            eventId,
            LocalDateTime.now(ZoneOffset.UTC)
        )

        if (hasSaleStarted && request.artist != null) {
            throw EventException(ErrorCode.EVENT_NOT_MODIFIABLE)
        }

        event.update(
            title = request.title,
            artist = request.artist,
            description = request.description
        )

        val response = EventDto.UpdateResponse.from(event)
        invalidateEventDetailCache(eventId)
        invalidateEventListCaches()
        return response
    }

    /**
     * 공연 Soft Delete - REQ-EVT-003
     *
     * 삭제 성공 후 공연 상세 및 목록 캐시를 무효화한다.
     */
    @Transactional
    fun deleteEvent(eventId: UUID): EventDto.DeleteResponse {
        val event = eventRepository.findByIdForUpdate(eventId)
            ?: throw EventException(ErrorCode.EVENT_NOT_FOUND)

        if (event.deletedAt != null) {
            throw EventException(ErrorCode.EVENT_ALREADY_DELETED)
        }

        if (seatRepository.existsByEventIdAndStatus(eventId, SeatStatus.SOLD)) {
            throw EventException(ErrorCode.EVENT_HAS_RESERVATIONS)
        }

        event.softDelete()

        invalidateEventDetailCache(eventId)
        invalidateEventListCaches()
        return EventDto.DeleteResponse(message = "Event deleted successfully")
    }

    /**
     * 공연 핵심 정보 조회 (내부 API용 — Reservation Service 등 타 서비스가 공연 메타 조립 시 사용)
     *
     * - 미존재 시: EVENT_NOT_FOUND (404)
     * - venue/hall은 fetch join으로 함께 로드되어 N+1 회피
     */
    fun getEventInfo(eventId: UUID): EventDto.EventInfoResponse {
        val event = eventRepository.findEventWithVenueAndHall(eventId)
            ?: throw EventException(ErrorCode.EVENT_NOT_FOUND)
        return EventDto.EventInfoResponse(
            eventId = event.id!!,
            title = event.title,
            artist = event.artist,
            venueName = event.venue.name,
            hallName = event.hall.name
        )
    }

    /**
     * 공연 핵심 정보 배치 조회 (내부 API용 — Reservation Service 예매 내역 페이지당 N개 공연 일괄 조립)
     *
     * - 미존재 ID는 응답에서 제외 (요청 size 와 응답 size 불일치 가능)
     * - venue/hall fetch join으로 단일 쿼리 N+1 회피
     */
    fun getEventInfoBatch(eventIds: List<UUID>): EventDto.EventInfoBatchResponse {
        if (eventIds.isEmpty()) return EventDto.EventInfoBatchResponse(emptyList())
        val events = eventRepository.findEventsWithVenueAndHall(eventIds)
        return EventDto.EventInfoBatchResponse(
            events.map { event ->
                EventDto.EventInfoResponse(
                    eventId = event.id!!,
                    title = event.title,
                    artist = event.artist,
                    venueName = event.venue.name,
                    hallName = event.hall.name
                )
            }
        )
    }

    // ===== Private Helper Methods =====

    private fun findActiveEvent(eventId: UUID): Event {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId)
            ?: throw EventException(ErrorCode.EVENT_NOT_FOUND)
    }

    private fun initializeSeats(
        schedule: EventSchedule,
        seatTemplate: SeatTemplateDto,
        priceByGrade: Map<SeatGrade, BigDecimal>
    ): List<Seat> {
        return seatTemplate.rows.flatMap { row ->
            val gradeStr = seatTemplate.gradeMapping[row]
                ?: throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING)
            val grade = try {
                SeatGrade.valueOf(gradeStr)
            } catch (e: IllegalArgumentException) {
                throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING)
            }
            val price = priceByGrade[grade]
                ?: throw EventException(ErrorCode.INVALID_INPUT)
            (1..seatTemplate.seatsPerRow).map { seatIndex ->
                Seat(eventSchedule = schedule, seatNumber = "${row}-${seatIndex}", grade = grade, price = price)
            }
        }
    }

    /**
     * 공연 상세 캐시 단건 삭제 (REQ-EVT-019)
     */
    internal fun invalidateEventDetailCache(eventId: UUID) {
        try {
            redisTemplate.delete("$EVENT_DETAIL_PREFIX$eventId")
        } catch (e: DataAccessException) {
            log.warn("Redis event detail cache invalidation failed for eventId: $eventId", e)
        }
    }

    /**
     * 공연 목록 캐시 전체 삭제 (REQ-EVT-019)
     *
     * SCAN 명령으로 `cache:event:list:*` 패턴 키를 모두 조회 후 일괄 삭제한다.
     * KEYS 명령 대신 SCAN을 사용하여 프로덕션 환경 성능 이슈를 방지한다.
     */
    internal fun invalidateEventListCaches() {
        try {
            redisTemplate.execute { conn ->
                val keys = mutableListOf<ByteArray>()
                conn.scan(
                    ScanOptions.scanOptions().match("${EVENT_LIST_PREFIX}*").count(100).build()
                ).use { cursor ->
                    cursor.forEach { keyBytes -> keys.add(keyBytes) }
                }
                if (keys.isNotEmpty()) {
                    conn.keyCommands().del(*keys.toTypedArray())
                }
                null
            }
        } catch (e: DataAccessException) {
            log.warn("Redis event list cache invalidation failed", e)
        }
    }

    private fun buildListCacheKey(
        page: Int,
        size: Int,
        status: EventStatus?,
        city: String?,
        keyword: String?
    ): String {
        val parts = mutableListOf<String>()
        status?.let { parts.add("s=${it.name}") }
        city?.let { parts.add("c=$it") }
        keyword?.let { parts.add("k=$it") }
        val filters = if (parts.isEmpty()) "all" else parts.joinToString(":")
        return "$EVENT_LIST_PREFIX$page:$size:$filters"
    }
}
