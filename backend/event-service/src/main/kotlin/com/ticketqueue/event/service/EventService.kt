package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
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
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공연(Event) 관리 서비스
 *
 * 공연의 CRUD 기능과 회차/좌석 일괄 생성을 처리한다.
 * - 공연 생성 시 Hall의 seatTemplate을 기반으로 회차별 좌석 자동 초기화
 * - Soft Delete: deleted_at 컬럼으로 논리 삭제, SOLD 좌석이 있으면 삭제 불가
 * - 수정 범위 차등: 판매 시작 후에는 artist 변경 불가 (REQ-EVT-002)
 */
@Service
@Transactional(readOnly = true)
class EventService(
    private val eventRepository: EventRepository,
    private val eventScheduleRepository: EventScheduleRepository,
    private val seatRepository: SeatRepository,
    private val venueRepository: VenueRepository,
    private val hallRepository: HallRepository,
    private val objectMapper: ObjectMapper
) {

    /**
     * 공연 생성 - 회차 및 좌석 일괄 생성 (REQ-EVT-001)
     *
     * 1. Venue/Hall 존재 및 귀속 검증
     * 2. 회차 순번 중복 및 시간 유효성 검증
     * 3. Hall seatTemplate 파싱
     * 4. Event → EventSchedule → Seat 순서로 저장
     */
    @Transactional
    fun createEvent(request: EventDto.CreateRequest): EventDto.CreateResponse {
        val venue = venueRepository.findById(request.venueId)
            .orElseThrow { EventException(ErrorCode.VENUE_NOT_FOUND) }

        val hall = hallRepository.findById(request.hallId)
            .orElseThrow { EventException(ErrorCode.HALL_NOT_FOUND) }

        // 홀이 해당 공연장에 속하는지 검증
        if (hall.venue.id != venue.id) {
            throw EventException(ErrorCode.HALL_NOT_IN_VENUE)
        }

        // 회차 순번 중복 검증
        val sequences = request.schedules.map { it.playSequence }
        if (sequences.size != sequences.toSet().size) {
            throw EventException(ErrorCode.DUPLICATE_PLAY_SEQUENCE)
        }

        // 회차 시간 유효성 검증
        request.schedules.forEach { s ->
            if (!s.eventStartAt.isBefore(s.eventEndAt) || !s.saleStartAt.isBefore(s.saleEndAt)) {
                throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
            }
            // 판매 종료 시각은 공연 시작 이전이어야 함 (티켓 판매는 공연 전에 마감)
            if (!s.saleEndAt.isBefore(s.eventStartAt)) {
                throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
            }
            // 공연 시작 시각은 현재 시각 이후여야 함 (과거 날짜 공연 등록 방지)
            if (!s.eventStartAt.isAfter(LocalDateTime.now())) {
                throw EventException(ErrorCode.INVALID_SCHEDULE_TIME)
            }
        }

        // 좌석 템플릿 파싱 (공연 저장 전 검증하여 fail-fast)
        val seatTemplate = try {
            objectMapper.readValue(hall.seatTemplate, SeatTemplateDto::class.java)
        } catch (e: JsonProcessingException) {
            throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE, cause = e)
        }

        val event = eventRepository.save(
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

        return EventDto.CreateResponse.from(event)
    }

    /**
     * 회차에 속하는 좌석 목록을 생성한다.
     *
     * Hall의 seatTemplate(rows × seatsPerRow)과 priceByGrade를 조합하여
     * 회차별 Seat 엔티티를 초기화한다.
     *
     * @param schedule    좌석이 속할 회차 엔티티
     * @param seatTemplate Hall에 저장된 좌석 배치 정보
     * @param priceByGrade 등급별 가격 (CreateRequest에서 전달)
     * @return 생성된 Seat 엔티티 목록 (아직 DB 미저장 상태)
     */
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
     * 공연 목록 조회 (페이징, 필터링, 검색) - REQ-EVT-004, P95 < 200ms
     */
    fun getEvents(
        page: Int,
        size: Int,
        status: EventStatus?,
        city: String?,
        keyword: String?
    ): Page<EventDto.ListResponse> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100))
        return eventRepository.findEventList(pageable, status, city, keyword)
    }

    /**
     * 공연 상세 조회 - REQ-EVT-005, P95 < 100ms
     *
     * 회차를 날짜별로 그룹핑하고, 회차별 매진 여부(isSoldOut)를 계산한다.
     * isSoldOut: AVAILABLE 좌석이 하나도 없으면 true
     */
    fun getEvent(eventId: UUID): EventDto.DetailResponse {
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

        return EventDto.DetailResponse(
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
    }

    /**
     * 공연 수정 (PATCH) - REQ-EVT-002
     *
     * 판매 시작 여부: 해당 공연의 어느 회차든 saleStartAt이 현재 시각 이전이면 "판매 시작됨"
     * - 판매 전: title, artist, description 모두 수정 가능
     * - 판매 후: title, description만 수정 가능 (artist 변경 시 409 반환)
     */
    @Transactional
    fun updateEvent(eventId: UUID, request: EventDto.UpdateRequest): EventDto.UpdateResponse {
        val event = findActiveEvent(eventId)
        // 전체 스케줄 로드 대신 EXISTS 쿼리 1개로 판매 시작 여부 확인 (N → 1 쿼리)
        val hasSaleStarted = eventScheduleRepository.existsByEventIdAndSaleStartAtLessThanEqual(eventId, LocalDateTime.now())

        if (hasSaleStarted && request.artist != null) {
            throw EventException(ErrorCode.EVENT_NOT_MODIFIABLE)
        }

        event.update(
            title = request.title,
            artist = request.artist,
            description = request.description
        )

        return EventDto.UpdateResponse.from(event)
    }

    /**
     * 공연 Soft Delete - REQ-EVT-003
     *
     * SOLD 좌석이 하나라도 있으면 삭제 불가 (데이터 정합성 보장)
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
        return EventDto.DeleteResponse(message = "Event deleted successfully")
    }

    private fun findActiveEvent(eventId: UUID): Event {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId)
            ?: throw EventException(ErrorCode.EVENT_NOT_FOUND)
    }
}
