package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import org.springframework.dao.DataIntegrityViolationException
import com.ticketqueue.event.config.CacheProperties
import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.entity.Venue
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.SeatRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class ScheduleServiceTest {

    private lateinit var eventRepository: EventRepository
    private lateinit var eventScheduleRepository: EventScheduleRepository
    private lateinit var seatRepository: SeatRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var redisTemplate: RedisTemplate<String, Any>
    private lateinit var valueOps: ValueOperations<String, Any>
    private lateinit var cacheHelper: CacheHelper
    private lateinit var eventService: EventService
    private lateinit var scheduleService: ScheduleService

    private val cacheProperties = CacheProperties()
    private val venueId = UUID.randomUUID()
    private val hallId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private fun createVenue() = Venue(
        id = venueId, name = "올림픽공원",
        address = "서울시 송파구", city = "서울",
        createdAt = now, updatedAt = now
    )

    private fun createHall(venue: Venue) = Hall(
        id = hallId, venue = venue,
        name = "KSPO DOME", capacity = 15000,
        seatTemplate = """{"rows":["A"],"seatsPerRow":2,"gradeMapping":{"A":"VIP"}}""",
        createdAt = now, updatedAt = now
    )

    private fun createEvent(venue: Venue, hall: Hall) = Event(
        id = eventId, title = "BTS World Tour", artist = "BTS",
        venue = venue, hall = hall, createdAt = now, updatedAt = now
    )

    private fun createSchedule(
        event: Event,
        status: ScheduleStatus = ScheduleStatus.UPCOMING
    ) = EventSchedule(
        id = scheduleId, event = event, playSequence = 1,
        eventStartAt = now.plusDays(30), eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = now.plusDays(1), saleEndAt = now.plusDays(29),
        status = status, createdAt = now, updatedAt = now
    )

    private val seatTemplate = SeatTemplateDto(
        rows = listOf("A"),
        seatsPerRow = 2,
        gradeMapping = mapOf("A" to "VIP")
    )

    private val priceByGrade = mapOf(SeatGrade.VIP to BigDecimal("100000"))

    private fun validCreateRequest(playSequence: Int = 1) = ScheduleDto.CreateRequest(
        playSequence = playSequence,
        eventStartAt = now.plusDays(30),
        eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = now.plusDays(1),
        saleEndAt = now.plusDays(29),
        priceByGrade = priceByGrade
    )

    @BeforeEach
    fun setUp() {
        eventRepository = mockk()
        eventScheduleRepository = mockk()
        seatRepository = mockk()
        objectMapper = mockk()
        redisTemplate = mockk()
        valueOps = mockk(relaxed = true)
        cacheHelper = mockk()
        eventService = mockk()

        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get(any<String>()) } returns null
        every { cacheHelper.tryAcquireStampedeLock(any(), any()) } returns true
        every { redisTemplate.delete(any<String>()) } returns true
        every { objectMapper.writeValueAsString(any()) } returns "{}"
        // EventService 캐시 무효화 메서드 기본 동작
        every { eventService.invalidateEventDetailCache(any()) } just runs
        every { eventService.invalidateEventListCaches() } just runs

        scheduleService = ScheduleService(
            eventRepository, eventScheduleRepository, seatRepository,
            objectMapper, redisTemplate, cacheProperties, cacheHelper, eventService
        )
    }

    @Nested
    @DisplayName("createSchedule")
    inner class CreateSchedule {

        @Test
        @DisplayName("성공적으로 회차와 좌석을 생성한다")
        fun success() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns seatTemplate
            every { eventScheduleRepository.save(any()) } returns schedule
            every { seatRepository.saveAll(any<List<Seat>>()) } returns emptyList()

            val result = scheduleService.createSchedule(eventId, validCreateRequest())

            assertEquals(scheduleId, result.id)
            assertEquals(eventId, result.eventId)
            assertEquals(1, result.playSequence)
            verify { seatRepository.saveAll(any<List<Seat>>()) }
        }

        @Test
        @DisplayName("생성 성공 시 부모 공연 상세 캐시와 목록 캐시를 무효화한다")
        fun cacheInvalidationOnCreate() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns seatTemplate
            every { eventScheduleRepository.save(any()) } returns schedule
            every { seatRepository.saveAll(any<List<Seat>>()) } returns emptyList()

            scheduleService.createSchedule(eventId, validCreateRequest())

            verify { eventService.invalidateEventDetailCache(eventId) }
            verify { eventService.invalidateEventListCaches() }
        }

        @Test
        @DisplayName("존재하지 않는 공연에 회차를 생성하면 EVENT_NOT_FOUND 예외가 발생한다")
        fun eventNotFound() {
            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns null

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, validCreateRequest()) }
            assertEquals(ErrorCode.EVENT_NOT_FOUND, ex.errorCode)
        }

        @Test
        @DisplayName("회차 순번이 중복되면 DUPLICATE_PLAY_SEQUENCE 예외가 발생한다")
        fun duplicatePlaySequence() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns true

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, validCreateRequest()) }
            assertEquals(ErrorCode.DUPLICATE_PLAY_SEQUENCE, ex.errorCode)
        }

        @Test
        @DisplayName("save() 시 DataIntegrityViolationException 발생하면 DUPLICATE_PLAY_SEQUENCE 예외로 변환된다 (race condition 방어)")
        fun duplicatePlaySequenceOnSave() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns seatTemplate
            every { eventScheduleRepository.save(any()) } throws DataIntegrityViolationException("duplicate key")

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, validCreateRequest()) }
            assertEquals(ErrorCode.DUPLICATE_PLAY_SEQUENCE, ex.errorCode)
        }

        @Test
        @DisplayName("공연 종료 시각이 시작 시각보다 빠르면 INVALID_SCHEDULE_TIME 예외가 발생한다")
        fun invalidEventTime() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val request = validCreateRequest().copy(
                eventStartAt = now.plusDays(30).plusHours(3),
                eventEndAt = now.plusDays(30)
            )

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, request) }
            assertEquals(ErrorCode.INVALID_SCHEDULE_TIME, ex.errorCode)
        }

        @Test
        @DisplayName("판매 종료 시각이 판매 시작 시각보다 빠르면 INVALID_SCHEDULE_TIME 예외가 발생한다")
        fun invalidSaleTime() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val request = validCreateRequest().copy(
                saleStartAt = now.plusDays(29),
                saleEndAt = now.plusDays(1)
            )

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, request) }
            assertEquals(ErrorCode.INVALID_SCHEDULE_TIME, ex.errorCode)
        }

        @Test
        @DisplayName("판매 종료 시각이 공연 시작 시각 이후이면 INVALID_SCHEDULE_TIME 예외가 발생한다")
        fun saleEndAfterEventStart() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val request = validCreateRequest().copy(
                saleEndAt = now.plusDays(31)
            )

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, request) }
            assertEquals(ErrorCode.INVALID_SCHEDULE_TIME, ex.errorCode)
        }

        @Test
        @DisplayName("공연 시작 시각이 현재보다 과거이면 INVALID_SCHEDULE_TIME 예외가 발생한다")
        fun eventStartInPast() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val request = validCreateRequest().copy(
                eventStartAt = now.minusDays(1),
                eventEndAt = now.minusDays(1).plusHours(2),
                saleStartAt = now.minusDays(30),
                saleEndAt = now.minusDays(2)
            )

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, request) }
            assertEquals(ErrorCode.INVALID_SCHEDULE_TIME, ex.errorCode)
        }

        @Test
        @DisplayName("좌석 템플릿 JSON 파싱 실패 시 INVALID_SEAT_TEMPLATE 예외가 발생한다")
        fun invalidSeatTemplate() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } throws
                object : JsonProcessingException("invalid") {}

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, validCreateRequest()) }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE, ex.errorCode)
        }

        @Test
        @DisplayName("행-등급 매핑이 누락되면 INVALID_SEAT_TEMPLATE_MAPPING 예외가 발생한다")
        fun invalidSeatTemplateMapping() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)
            val templateWithMissingGrade = SeatTemplateDto(
                rows = listOf("A", "B"),
                seatsPerRow = 1,
                gradeMapping = mapOf("A" to "VIP")
            )

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns templateWithMissingGrade
            every { eventScheduleRepository.save(any()) } returns schedule

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, validCreateRequest()) }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING, ex.errorCode)
        }

        @Test
        @DisplayName("등급별 가격이 누락되면 INVALID_INPUT 예외가 발생한다")
        fun missingGradePrice() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)
            val templateWithSGrade = SeatTemplateDto(
                rows = listOf("A"),
                seatsPerRow = 1,
                gradeMapping = mapOf("A" to "S")
            )
            val requestWithoutSPrice = validCreateRequest().copy(
                priceByGrade = mapOf(SeatGrade.VIP to BigDecimal("100000"))
            )

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.existsByEventIdAndPlaySequence(eventId, 1) } returns false
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns templateWithSGrade
            every { eventScheduleRepository.save(any()) } returns schedule

            val ex = assertThrows<EventException> { scheduleService.createSchedule(eventId, requestWithoutSPrice) }
            assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        }
    }

    @Nested
    @DisplayName("getSchedules")
    inner class GetSchedules {

        @Test
        @DisplayName("회차 목록을 isSoldOut 혼합으로 반환한다")
        fun success() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val scheduleId2 = UUID.randomUUID()
            val schedule1 = createSchedule(event)
            val schedule2 = EventSchedule(
                id = scheduleId2, event = event, playSequence = 2,
                eventStartAt = now.plusDays(60), eventEndAt = now.plusDays(60).plusHours(2),
                saleStartAt = now.plusDays(1), saleEndAt = now.plusDays(59),
                createdAt = now, updatedAt = now
            )

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(schedule1, schedule2)
            every { eventRepository.findScheduleIdsWithAvailableSeats(any()) } returns setOf(scheduleId)

            val result = scheduleService.getSchedules(eventId)

            assertEquals(2, result.size)
            assertFalse(result[0].isSoldOut)
            assertTrue(result[1].isSoldOut)
        }

        @Test
        @DisplayName("존재하지 않는 공연의 회차 목록 조회 시 EVENT_NOT_FOUND 예외가 발생한다")
        fun eventNotFound() {
            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns null

            val ex = assertThrows<EventException> { scheduleService.getSchedules(eventId) }
            assertEquals(ErrorCode.EVENT_NOT_FOUND, ex.errorCode)
        }

        @Test
        @DisplayName("회차가 없는 공연은 빈 목록을 반환한다")
        fun emptySchedules() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns emptyList()

            val result = scheduleService.getSchedules(eventId)

            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("getSchedule")
    inner class GetSchedule {

        @Test
        @DisplayName("회차 상세를 조회한다")
        fun success() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { eventScheduleRepository.findById(scheduleId) } returns Optional.of(schedule)
            every { eventRepository.findScheduleIdsWithAvailableSeats(listOf(scheduleId)) } returns setOf(scheduleId)

            val result = scheduleService.getSchedule(scheduleId)

            assertEquals(scheduleId, result.id)
            assertEquals(eventId, result.eventId)
            assertEquals("BTS World Tour", result.eventTitle)
            assertFalse(result.isSoldOut)
        }

        @Test
        @DisplayName("존재하지 않는 회차 조회 시 SCHEDULE_NOT_FOUND 예외가 발생한다")
        fun notFound() {
            every { valueOps.get(any<String>()) } returns null
            every { eventScheduleRepository.findById(scheduleId) } returns Optional.empty()

            val ex = assertThrows<EventException> { scheduleService.getSchedule(scheduleId) }
            assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, ex.errorCode)
        }

        @Test
        @DisplayName("AVAILABLE 좌석이 없으면 isSoldOut이 true이다")
        fun soldOut() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { eventScheduleRepository.findById(scheduleId) } returns Optional.of(schedule)
            every { eventRepository.findScheduleIdsWithAvailableSeats(listOf(scheduleId)) } returns emptySet()

            val result = scheduleService.getSchedule(scheduleId)

            assertTrue(result.isSoldOut)
        }

        @Test
        @DisplayName("캐시 Hit - DB 조회 없이 캐시에서 즉시 반환한다")
        fun cacheHit() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)
            val detailResponse = ScheduleDto.DetailResponse.from(schedule, isSoldOut = false)
            val cacheKey = "cache:schedule:$scheduleId"
            val cachedJson = "{\"cached\":\"schedule\"}"

            every { valueOps.get(cacheKey) } returns cachedJson
            every { objectMapper.readValue(cachedJson, ScheduleDto.DetailResponse::class.java) } returns detailResponse

            val result = scheduleService.getSchedule(scheduleId)

            assertEquals(scheduleId, result.id)
            verify(exactly = 0) { eventScheduleRepository.findById(any()) }
        }

        @Test
        @DisplayName("캐시 Miss - DB 조회 후 캐시에 저장한다")
        fun cacheMissWritesToCache() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { eventScheduleRepository.findById(scheduleId) } returns Optional.of(schedule)
            every { eventRepository.findScheduleIdsWithAvailableSeats(listOf(scheduleId)) } returns setOf(scheduleId)

            scheduleService.getSchedule(scheduleId)

            verify { objectMapper.writeValueAsString(any<ScheduleDto.DetailResponse>()) }
            verify { valueOps.set("cache:schedule:$scheduleId", any(), any<Duration>()) }
        }

        @Test
        @DisplayName("Redis 장애 시 DB fallback으로 정상 응답한다")
        fun redisFallback() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { valueOps.get(any<String>()) } throws RedisConnectionFailureException("connection failed")
            every { eventScheduleRepository.findById(scheduleId) } returns Optional.of(schedule)
            every { eventRepository.findScheduleIdsWithAvailableSeats(listOf(scheduleId)) } returns setOf(scheduleId)

            val result = scheduleService.getSchedule(scheduleId)

            assertEquals(scheduleId, result.id)
        }
    }

    @Nested
    @DisplayName("getCleanupTargetScheduleIds")
    inner class GetCleanupTargetScheduleIds {

        @Test
        @DisplayName("repository가 반환한 UUID 목록을 그대로 반환한다")
        fun success() {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()

            every { eventScheduleRepository.findCleanupTargetScheduleIds(any()) } returns listOf(id1, id2)

            val result = scheduleService.getCleanupTargetScheduleIds()

            assertEquals(listOf(id1, id2), result)
        }

        @Test
        @DisplayName("정리 대상 회차가 없으면 빈 목록을 반환한다")
        fun empty() {
            every { eventScheduleRepository.findCleanupTargetScheduleIds(any()) } returns emptyList()

            val result = scheduleService.getCleanupTargetScheduleIds()

            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("changeScheduleStatus")
    inner class ChangeScheduleStatus {

        @Test
        @DisplayName("유효한 상태 전이가 성공한다 (UPCOMING → ONGOING)")
        fun validTransition() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.UPCOMING)
            val request = ScheduleDto.ChangeStatusRequest(status = ScheduleStatus.ONGOING)

            every { eventScheduleRepository.findById(scheduleId) } returns Optional.of(schedule)

            val result = scheduleService.changeScheduleStatus(scheduleId, request)

            assertEquals(ScheduleStatus.UPCOMING, result.previousStatus)
            assertEquals(ScheduleStatus.ONGOING, result.currentStatus)
            assertEquals(scheduleId, result.id)
        }

        @Test
        @DisplayName("무효한 상태 전이 시 INVALID_SCHEDULE_STATUS 예외가 발생한다 (ENDED → UPCOMING)")
        fun invalidTransition() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.ENDED)
            val request = ScheduleDto.ChangeStatusRequest(status = ScheduleStatus.UPCOMING)

            every { eventScheduleRepository.findById(scheduleId) } returns Optional.of(schedule)

            val ex = assertThrows<EventException> { scheduleService.changeScheduleStatus(scheduleId, request) }
            assertEquals(ErrorCode.INVALID_SCHEDULE_STATUS, ex.errorCode)
        }

        @Test
        @DisplayName("존재하지 않는 회차 상태 변경 시 SCHEDULE_NOT_FOUND 예외가 발생한다")
        fun notFound() {
            every { eventScheduleRepository.findById(scheduleId) } returns Optional.empty()

            val ex = assertThrows<EventException> {
                scheduleService.changeScheduleStatus(scheduleId, ScheduleDto.ChangeStatusRequest(ScheduleStatus.ONGOING))
            }
            assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, ex.errorCode)
        }

        @Test
        @DisplayName("상태 변경 성공 시 회차 상세, 공연 상세, 목록 캐시를 모두 무효화한다")
        fun cacheInvalidationOnStatusChange() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, ScheduleStatus.UPCOMING)
            val request = ScheduleDto.ChangeStatusRequest(status = ScheduleStatus.ONGOING)

            every { eventScheduleRepository.findById(scheduleId) } returns Optional.of(schedule)

            scheduleService.changeScheduleStatus(scheduleId, request)

            verify { redisTemplate.delete("cache:schedule:$scheduleId") }
            verify { eventService.invalidateEventDetailCache(eventId) }
            verify { eventService.invalidateEventListCaches() }
        }
    }
}
