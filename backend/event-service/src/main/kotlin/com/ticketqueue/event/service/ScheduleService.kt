package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.SeatRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * 공연 회차(EventSchedule) 관리 서비스
 *
 * 공연에 회차를 추가하거나 개별 회차를 조회/상태 관리한다.
 * - 회차 생성 시 Hall seatTemplate 기반 좌석 자동 초기화 (EventService와 동일 로직)
 * - 상태 전이: UPCOMING → ONGOING/CANCELLED, ONGOING → ENDED/CANCELLED (도메인 메서드 위임)
 * - REQ-EVT-001, REQ-EVT-007
 */
@Service
@Transactional(readOnly = true)
class ScheduleService(
    private val eventRepository: EventRepository,
    private val eventScheduleRepository: EventScheduleRepository,
    private val seatRepository: SeatRepository,
    private val objectMapper: ObjectMapper
) {

    /**
     * 특정 공연에 회차를 추가한다 (좌석 자동 초기화 포함)
     *
     * 1. Event 존재 및 softDelete 여부 확인
     * 2. playSequence 중복 검증
     * 3. 시간 유효성 검증 (4가지 규칙)
     * 4. seatTemplate 파싱 (fail-fast)
     * 5. EventSchedule 저장 → 좌석 일괄 초기화
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

        val schedule = eventScheduleRepository.save(
            EventSchedule(
                event = event,
                playSequence = request.playSequence,
                eventStartAt = request.eventStartAt,
                eventEndAt = request.eventEndAt,
                saleStartAt = request.saleStartAt,
                saleEndAt = request.saleEndAt
            )
        )

        val seats = initializeSeats(schedule, seatTemplate, request.priceByGrade)
        seatRepository.saveAll(seats)

        return ScheduleDto.CreateResponse.from(schedule)
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
     * 회차 상세를 조회한다
     *
     * LAZY event 접근으로 추가 쿼리 1회 발생 (단건 조회라 허용)
     */
    fun getSchedule(scheduleId: UUID): ScheduleDto.DetailResponse {
        val schedule = eventScheduleRepository.findById(scheduleId)
            .orElseThrow { EventException(ErrorCode.SCHEDULE_NOT_FOUND) }

        val availableScheduleIds = eventRepository.findScheduleIdsWithAvailableSeats(listOf(scheduleId))
        val isSoldOut = scheduleId !in availableScheduleIds

        return ScheduleDto.DetailResponse.from(schedule, isSoldOut)
    }

    /**
     * 회차 상태를 변경한다
     *
     * 유효하지 않은 전이 시 EventSchedule.changeStatus()에서 INVALID_SCHEDULE_STATUS 예외 발생
     */
    @Transactional
    fun changeScheduleStatus(scheduleId: UUID, request: ScheduleDto.ChangeStatusRequest): ScheduleDto.ChangeStatusResponse {
        val schedule = eventScheduleRepository.findById(scheduleId)
            .orElseThrow { EventException(ErrorCode.SCHEDULE_NOT_FOUND) }

        val previousStatus = schedule.status
        schedule.changeStatus(request.status)

        return ScheduleDto.ChangeStatusResponse(
            id = schedule.id!!,
            previousStatus = previousStatus,
            currentStatus = schedule.status,
            updatedAt = schedule.updatedAt!!
        )
    }

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
        if (!eventStartAt.isAfter(LocalDateTime.now())) {
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
                throw EventException(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING)
            }
            val price = priceByGrade[grade]
                ?: throw EventException(ErrorCode.INVALID_INPUT)
            (1..seatTemplate.seatsPerRow).map { seatIndex ->
                Seat(eventSchedule = schedule, seatNumber = "${row}-${seatIndex}", grade = grade, price = price)
            }
        }
    }
}
