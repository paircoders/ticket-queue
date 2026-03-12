package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.util.DateTimeUtils
import com.ticketqueue.event.config.CacheProperties
import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.SeatRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공연 회차(EventSchedule) 관리 서비스
 *
 * 공연에 회차를 추가하거나 개별 회차를 조회/상태 관리한다.
 * - 회차 생성 시 Hall seatTemplate 기반 좌석 자동 초기화 (EventService와 동일 로직)
 * - 상태 전이: UPCOMING → ONGOING/CANCELLED, ONGOING → ENDED/CANCELLED (도메인 메서드 위임)
 * - REQ-EVT-001, REQ-EVT-007
 *
 * Cache-Aside 전략 (REQ-EVT-017):
 * - 회차 상세: `cache:schedule:{scheduleId}` (String JSON, TTL 5분)
 * - Cache Stampede 방지: Lua 스크립트 락 (REQ-EVT-021)
 * - 캐시 무효화: 회차 생성/상태 변경 시 관련 캐시 제거 (REQ-EVT-019)
 */
@Service
@Transactional(readOnly = true)
class ScheduleService(
    private val eventRepository: EventRepository,
    private val eventScheduleRepository: EventScheduleRepository,
    private val seatRepository: SeatRepository,
    private val objectMapper: ObjectMapper,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val cacheProperties: CacheProperties,
    private val cacheHelper: CacheHelper,
    private val eventService: EventService
) {

    private val log = LoggerFactory.getLogger(ScheduleService::class.java)

    private companion object {
        const val SCHEDULE_DETAIL_PREFIX = "cache:schedule:"
        const val SCHEDULE_LOCK_PREFIX = "cache:lock:schedule:"
        const val STAMPEDE_LOCK_TTL = 10L
    }

    /**
     * 특정 공연에 회차를 추가한다 (좌석 자동 초기화 포함)
     *
     * 생성 성공 후 부모 공연 상세 캐시와 목록 캐시를 무효화한다.
     */
    @Transactional
    fun createSchedule(eventId: UUID, request: ScheduleDto.CreateRequest): ScheduleDto.CreateResponse {
        val event = eventRepository.findByIdAndDeletedAtIsNull(eventId)
            ?: throw EventException(ErrorCode.EVENT_NOT_FOUND)

        if (eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, request.playSequence)) {
            throw EventException(ErrorCode.DUPLICATE_PLAY_SEQUENCE)
        }

        validateScheduleTime(request.eventStartAt, request.eventEndAt, request.saleStartAt, request.saleEndAt)

        val seatTemplate = try {
            objectMapper.readValue(event.hall.seatTemplate, SeatTemplateDto::class.java)
        } catch (e: JsonProcessingException) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE, cause = e)
        }

        val schedule = try {
            eventScheduleRepository.save(
                EventSchedule(
                    event = event,
                    playSequence = request.playSequence,
                    eventStartAt = request.eventStartAt,
                    eventEndAt = request.eventEndAt,
                    saleStartAt = request.saleStartAt,
                    saleEndAt = request.saleEndAt
                )
            )
        } catch (e: DataIntegrityViolationException) {
            throw EventException(ErrorCode.DUPLICATE_PLAY_SEQUENCE, cause = e)
        }

        val seats = initializeSeats(schedule, seatTemplate, request.priceByGrade)
        seatRepository.saveAll(seats)

        val response = ScheduleDto.CreateResponse.from(schedule)
        // 부모 공연 상세 캐시 및 목록 캐시 무효화
        eventService.invalidateEventDetailCache(eventId)
        eventService.invalidateEventListCaches()
        return response
    }

    /**
     * 특정 공연의 회차 목록을 조회한다
     *
     * isSoldOut: AVAILABLE 좌석이 하나도 없으면 true
     */
    fun getSchedules(eventId: UUID): List<ScheduleDto.ListResponse> {
        eventRepository.findByIdAndDeletedAtIsNull(eventId)
            ?: throw EventException(ErrorCode.EVENT_NOT_FOUND)

        val schedules = eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId)
        if (schedules.isEmpty()) return emptyList()

        val scheduleIds = schedules.map { it.id!! }
        val availableScheduleIds = eventRepository.findScheduleIdsWithAvailableSeats(scheduleIds)

        return schedules.map { schedule ->
            ScheduleDto.ListResponse.from(schedule, isSoldOut = schedule.id!! !in availableScheduleIds)
        }
    }

    /**
     * 회차 상세를 조회한다 - Cache-Aside 적용
     *
     * Cache-Aside 전략:
     * 1. String JSON 캐시 조회 → hit 시 즉시 반환
     * 2. miss 시 → Stampede Lock 시도 → DB 조회 → 캐시 저장
     */
    fun getSchedule(scheduleId: UUID): ScheduleDto.DetailResponse {
        val cacheKey = "$SCHEDULE_DETAIL_PREFIX$scheduleId"

        // 1. Cache read
        try {
            val cached = redisTemplate.opsForValue().get(cacheKey) as? String
            if (cached != null) {
                return objectMapper.readValue(cached, ScheduleDto.DetailResponse::class.java)
            }
        } catch (e: DataAccessException) {
            log.warn("Redis cache read failed for key: $cacheKey", e)
        } catch (e: JsonProcessingException) {
            log.warn("Redis cache deserialization failed for key: $cacheKey, skipping cache", e)
            try { redisTemplate.delete(cacheKey) } catch (ignored: DataAccessException) {}
        }

        // 2. Stampede Lock
        val lockKey = "$SCHEDULE_LOCK_PREFIX$scheduleId"
        val lockAcquired = cacheHelper.tryAcquireStampedeLock(lockKey, STAMPEDE_LOCK_TTL)

        // 3. DB query
        val schedule = eventScheduleRepository.findById(scheduleId)
            .orElseThrow { EventException(ErrorCode.SCHEDULE_NOT_FOUND) }

        val availableScheduleIds = eventRepository.findScheduleIdsWithAvailableSeats(listOf(scheduleId))
        val isSoldOut = scheduleId !in availableScheduleIds
        val detailResponse = ScheduleDto.DetailResponse.from(schedule, isSoldOut)

        // 4. Cache write (락 획득 성공 시에만)
        if (lockAcquired) {
            try {
                val json = objectMapper.writeValueAsString(detailResponse)
                redisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(cacheProperties.schedule.ttl))
            } catch (e: DataAccessException) {
                log.warn("Redis cache write failed for key: $cacheKey", e)
            } catch (e: JsonProcessingException) {
                log.warn("Redis cache serialization failed for key: $cacheKey", e)
            }
        }

        return detailResponse
    }

    /**
     * 회차 상태를 변경한다
     *
     * 상태 변경 성공 후 회차 상세, 부모 공연 상세, 공연 목록 캐시를 무효화한다.
     * 유효하지 않은 전이 시 EventSchedule.changeStatus()에서 INVALID_SCHEDULE_STATUS 예외 발생
     */
    @Transactional
    fun changeScheduleStatus(scheduleId: UUID, request: ScheduleDto.ChangeStatusRequest): ScheduleDto.ChangeStatusResponse {
        val schedule = eventScheduleRepository.findById(scheduleId)
            .orElseThrow { EventException(ErrorCode.SCHEDULE_NOT_FOUND) }

        val previousStatus = schedule.status
        schedule.changeStatus(request.status)

        val eventId = schedule.event.id!!
        invalidateScheduleDetailCache(scheduleId)
        eventService.invalidateEventDetailCache(eventId)
        eventService.invalidateEventListCaches()

        return ScheduleDto.ChangeStatusResponse(
            id = schedule.id!!,
            previousStatus = previousStatus,
            currentStatus = schedule.status,
            updatedAt = schedule.updatedAt!!
        )
    }

    // ===== Private Helper Methods =====

    private fun validateScheduleTime(
        eventStartAt: LocalDateTime,
        eventEndAt: LocalDateTime,
        saleStartAt: LocalDateTime,
        saleEndAt: LocalDateTime
    ) {
        if (!eventStartAt.isBefore(eventEndAt) || !saleStartAt.isBefore(saleEndAt)) {
            throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
        }
        if (!saleEndAt.isBefore(eventStartAt)) {
            throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
        }
        if (!eventStartAt.isAfter(DateTimeUtils.now())) {
            throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
        }
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
                throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING, cause = e)
            }
            val price = priceByGrade[grade]
                ?: throw EventException(ErrorCode.INVALID_INPUT)
            (1..seatTemplate.seatsPerRow).map { seatIndex ->
                Seat(eventSchedule = schedule, seatNumber = "${row}-${seatIndex}", grade = grade, price = price)
            }
        }
    }

    /**
     * 회차의 티켓 판매 가능 여부를 반환한다 (내부 API용 — Queue Service 검증 전용)
     *
     * - 회차 없음: SCHEDULE_NOT_FOUND 예외 (404)
     * - CANCELLED/ENDED 상태: sellable=false, reason=SCHEDULE_NOT_AVAILABLE
     * - 판매 시작 전: sellable=false, reason=TICKET_SALE_NOT_STARTED
     * - 판매 종료 후: sellable=false, reason=TICKET_SALE_ENDED
     * - 그 외: sellable=true
     */
    fun checkSellable(scheduleId: UUID): ScheduleDto.SellableResponse {
        val schedule = eventScheduleRepository.findById(scheduleId)
            .orElseThrow { EventException(ErrorCode.SCHEDULE_NOT_FOUND) }

        if (schedule.status == ScheduleStatus.CANCELLED || schedule.status == ScheduleStatus.ENDED) {
            return ScheduleDto.SellableResponse(sellable = false, reason = "SCHEDULE_NOT_AVAILABLE")
        }

        val now = LocalDateTime.now()
        if (now.isBefore(schedule.saleStartAt)) {
            return ScheduleDto.SellableResponse(sellable = false, reason = "TICKET_SALE_NOT_STARTED")
        }
        if (now.isAfter(schedule.saleEndAt)) {
            return ScheduleDto.SellableResponse(sellable = false, reason = "TICKET_SALE_ENDED")
        }

        return ScheduleDto.SellableResponse(sellable = true)
    }

    /**
     * 종료/취소 후 24시간 이상 경과한 회차 ID 목록을 반환한다 (내부 API용 — Queue Service 정리 배치 전용)
     */
    fun getCleanupTargetScheduleIds(): List<UUID> {
        val cutoffTime = DateTimeUtils.now().minusHours(24)
        return eventScheduleRepository.findCleanupTargetScheduleIds(cutoffTime)
    }

    internal fun invalidateScheduleDetailCache(scheduleId: UUID) {
        try {
            redisTemplate.delete("$SCHEDULE_DETAIL_PREFIX$scheduleId")
        } catch (e: DataAccessException) {
            log.warn("Redis schedule cache invalidation failed for scheduleId: $scheduleId", e)
        }
    }
}
