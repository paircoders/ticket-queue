package com.ticketqueue.event.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.entity.Event
import com.ticketqueue.event.entity.EventSchedule
import com.ticketqueue.event.entity.EventStatus
import com.ticketqueue.event.entity.Hall
import com.ticketqueue.event.entity.Seat
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.entity.SeatStatus
import com.ticketqueue.event.entity.Venue
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.repository.EventRepository
import com.ticketqueue.event.repository.EventScheduleRepository
import com.ticketqueue.event.repository.HallRepository
import com.ticketqueue.event.repository.SeatRepository
import com.ticketqueue.event.repository.VenueRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class EventServiceTest {

    private lateinit var eventRepository: EventRepository
    private lateinit var eventScheduleRepository: EventScheduleRepository
    private lateinit var seatRepository: SeatRepository
    private lateinit var venueRepository: VenueRepository
    private lateinit var hallRepository: HallRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var eventService: EventService

    private val venueId = UUID.randomUUID()
    private val hallId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private fun createVenue() = Venue(
        id = venueId,
        name = "올림픽공원",
        address = "서울시 송파구",
        city = "서울",
        createdAt = now,
        updatedAt = now
    )

    private fun createHall(venue: Venue) = Hall(
        id = hallId,
        venue = venue,
        name = "KSPO DOME",
        capacity = 15000,
        seatTemplate = """{"rows":["A"],"seatsPerRow":2,"gradeMapping":{"A":"VIP"}}""",
        createdAt = now,
        updatedAt = now
    )

    private fun createEvent(venue: Venue, hall: Hall) = Event(
        id = eventId,
        title = "BTS World Tour",
        artist = "BTS",
        venue = venue,
        hall = hall,
        createdAt = now,
        updatedAt = now
    )

    private fun createSchedule(
        event: Event,
        saleStartAt: LocalDateTime = now.plusDays(1)
    ) = EventSchedule(
        id = scheduleId,
        event = event,
        playSequence = 1,
        eventStartAt = now.plusDays(30),
        eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = saleStartAt,
        saleEndAt = now.plusDays(29),
        createdAt = now,
        updatedAt = now
    )

    @BeforeEach
    fun setUp() {
        eventRepository = mockk()
        eventScheduleRepository = mockk()
        seatRepository = mockk()
        venueRepository = mockk()
        hallRepository = mockk()
        objectMapper = mockk()
        eventService = EventService(
            eventRepository, eventScheduleRepository, seatRepository,
            venueRepository, hallRepository, objectMapper
        )
    }

    @Nested
    @DisplayName("createEvent")
    inner class CreateEvent {

        private val seatTemplate = SeatTemplateDto(
            rows = listOf("A"),
            seatsPerRow = 2,
            gradeMapping = mapOf("A" to "VIP")
        )
        private val priceByGrade = mapOf(SeatGrade.VIP to BigDecimal("100000"))
        private val scheduleRequest = EventDto.ScheduleRequest(
            playSequence = 1,
            eventStartAt = now.plusDays(30),
            eventEndAt = now.plusDays(30).plusHours(2),
            saleStartAt = now.plusDays(1),
            saleEndAt = now.plusDays(29)
        )

        @Test
        @DisplayName("성공적으로 공연과 회차, 좌석을 생성한다")
        fun success() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)
            val request = EventDto.CreateRequest(
                title = "BTS World Tour",
                artist = "BTS",
                venueId = venueId,
                hallId = hallId,
                priceByGrade = priceByGrade,
                schedules = listOf(scheduleRequest)
            )

            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns seatTemplate
            every { eventRepository.save(any()) } returns event
            every { eventScheduleRepository.save(any()) } returns schedule
            every { seatRepository.saveAll(any<List<Seat>>()) } returns emptyList()

            val result = eventService.createEvent(request)

            assertEquals(eventId, result.id)
            assertEquals("BTS World Tour", result.title)
            verify { seatRepository.saveAll(any<List<Seat>>()) }
        }

        @Test
        @DisplayName("홀이 공연장에 속하지 않으면 예외가 발생한다")
        fun hallNotInVenue() {
            val venue = createVenue()
            val differentVenue = Venue(
                id = UUID.randomUUID(), name = "다른 공연장",
                address = "부산시 해운대구", city = "부산", createdAt = now, updatedAt = now
            )
            val hall = createHall(differentVenue)
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade, schedules = listOf(scheduleRequest)
            )

            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.HALL_NOT_IN_VENUE, exception.errorCode)
        }

        @Test
        @DisplayName("회차 순번이 중복되면 예외가 발생한다")
        fun duplicatePlaySequence() {
            val venue = createVenue()
            val hall = createHall(venue)
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade,
                schedules = listOf(
                    scheduleRequest,
                    scheduleRequest.copy(eventStartAt = now.plusDays(60)) // playSequence 중복
                )
            )

            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.DUPLICATE_PLAY_SEQUENCE, exception.errorCode)
        }

        @Test
        @DisplayName("회차 종료 시각이 시작 시각보다 빠르면 예외가 발생한다")
        fun invalidScheduleTime() {
            val venue = createVenue()
            val hall = createHall(venue)
            val invalidSchedule = scheduleRequest.copy(
                eventStartAt = now.plusDays(30).plusHours(3),
                eventEndAt = now.plusDays(30) // 종료가 시작보다 앞섬
            )
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade, schedules = listOf(invalidSchedule)
            )

            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.INVALID_SCHEDULE_TIME, exception.errorCode)
        }

        @Test
        @DisplayName("공연장이 존재하지 않으면 VENUE_NOT_FOUND 예외가 발생한다")
        fun venueNotFound() {
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade, schedules = listOf(scheduleRequest)
            )
            every { venueRepository.findById(venueId) } returns Optional.empty()

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.VENUE_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("홀이 존재하지 않으면 HALL_NOT_FOUND 예외가 발생한다")
        fun hallNotFound() {
            val venue = createVenue()
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade, schedules = listOf(scheduleRequest)
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.empty()

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.HALL_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("좌석 템플릿 JSON 파싱 실패 시 INVALID_SEAT_TEMPLATE 예외가 발생한다")
        fun invalidSeatTemplate() {
            val venue = createVenue()
            val hall = createHall(venue)
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade, schedules = listOf(scheduleRequest)
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } throws
                object : JsonProcessingException("invalid template") {}

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE, exception.errorCode)
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
                gradeMapping = mapOf("A" to "VIP") // B 행 매핑 없음
            )
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade, schedules = listOf(scheduleRequest)
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns templateWithMissingGrade
            every { eventRepository.save(any()) } returns event
            every { eventScheduleRepository.save(any()) } returns schedule

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.INVALID_SEAT_TEMPLATE_MAPPING, exception.errorCode)
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
                gradeMapping = mapOf("A" to "S") // S 등급인데 가격 없음
            )
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = mapOf(SeatGrade.VIP to BigDecimal("100000")), // S 가격 없음
                schedules = listOf(scheduleRequest)
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)
            every { objectMapper.readValue(any<String>(), SeatTemplateDto::class.java) } returns templateWithSGrade
            every { eventRepository.save(any()) } returns event
            every { eventScheduleRepository.save(any()) } returns schedule

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.INVALID_INPUT, exception.errorCode)
        }

        @Test
        @DisplayName("판매 종료 시각이 판매 시작 시각보다 빠르면 INVALID_SCHEDULE_TIME 예외가 발생한다")
        fun invalidSaleTime() {
            val venue = createVenue()
            val hall = createHall(venue)
            val invalidSchedule = scheduleRequest.copy(
                saleStartAt = now.plusDays(29),
                saleEndAt = now.plusDays(1) // saleEnd < saleStart
            )
            val request = EventDto.CreateRequest(
                title = "BTS World Tour", artist = "BTS",
                venueId = venueId, hallId = hallId,
                priceByGrade = priceByGrade, schedules = listOf(invalidSchedule)
            )
            every { venueRepository.findById(venueId) } returns Optional.of(venue)
            every { hallRepository.findById(hallId) } returns Optional.of(hall)

            val exception = assertThrows<EventException> { eventService.createEvent(request) }
            assertEquals(ErrorCode.INVALID_SCHEDULE_TIME, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("getEvents")
    inner class GetEvents {

        @Test
        @DisplayName("공연 목록을 페이징으로 조회한다")
        fun success() {
            val listItem = EventDto.ListResponse(
                id = eventId,
                title = "BTS World Tour",
                artist = "BTS",
                venueName = "올림픽공원",
                startDate = now.plusDays(30),
                endDate = now.plusDays(31),
                status = EventStatus.PREPARING
            )
            val pageable = PageRequest.of(0, 20)
            val page = PageImpl(listOf(listItem), pageable, 1)
            every { eventRepository.findEventList(any(), null, null, null) } returns page

            val result = eventService.getEvents(0, 20, null, null, null)

            assertEquals(1, result.totalElements)
            assertEquals("BTS World Tour", result.content[0].title)
        }

        @Test
        @DisplayName("status 필터 전달 시 findEventList에 status 파라미터가 전달된다")
        fun withStatusFilter() {
            val pageable = PageRequest.of(0, 20)
            val page = PageImpl(emptyList<EventDto.ListResponse>(), pageable, 0)
            every { eventRepository.findEventList(any(), EventStatus.OPEN, null, null) } returns page

            val result = eventService.getEvents(0, 20, EventStatus.OPEN, null, null)

            assertEquals(0, result.totalElements)
            verify { eventRepository.findEventList(any(), EventStatus.OPEN, null, null) }
        }

        @Test
        @DisplayName("음수 page는 0으로, 최대 초과 size는 100으로 보정된다")
        fun pageSizeCoerce() {
            val pageable = PageRequest.of(0, 100)
            val page = PageImpl(emptyList<EventDto.ListResponse>(), pageable, 0)
            every { eventRepository.findEventList(any(), null, null, null) } returns page

            eventService.getEvents(-1, 200, null, null, null)

            verify { eventRepository.findEventList(match { it.pageNumber == 0 && it.pageSize == 100 }, null, null, null) }
        }
    }

    @Nested
    @DisplayName("getEvent")
    inner class GetEvent {

        @Test
        @DisplayName("공연 상세를 조회한다 (회차 날짜별 그룹핑)")
        fun success() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { eventRepository.findEventWithVenueAndHall(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(schedule)
            every { eventRepository.findScheduleIdsWithAvailableSeats(any()) } returns setOf(scheduleId)

            val result = eventService.getEvent(eventId)

            assertEquals(eventId, result.id)
            assertEquals("BTS World Tour", result.title)
            assertEquals(1, result.schedules.size)
            assertEquals(1, result.schedules[0].times.size)
        }

        @Test
        @DisplayName("존재하지 않는 공연 조회 시 예외가 발생한다")
        fun notFound() {
            every { eventRepository.findEventWithVenueAndHall(eventId) } returns null

            val exception = assertThrows<EventException> { eventService.getEvent(eventId) }
            assertEquals(ErrorCode.EVENT_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("복수 날짜의 회차가 날짜별로 그룹핑된다")
        fun multipleDateGroups() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val scheduleId2 = UUID.randomUUID()
            val schedule1 = createSchedule(event)
            val schedule2 = EventSchedule(
                id = scheduleId2, event = event, playSequence = 2,
                eventStartAt = now.plusDays(31), eventEndAt = now.plusDays(31).plusHours(2),
                saleStartAt = now.plusDays(1), saleEndAt = now.plusDays(29),
                createdAt = now, updatedAt = now
            )

            every { eventRepository.findEventWithVenueAndHall(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(schedule1, schedule2)
            every { eventRepository.findScheduleIdsWithAvailableSeats(any()) } returns setOf(scheduleId, scheduleId2)

            val result = eventService.getEvent(eventId)

            assertEquals(2, result.schedules.size)
            assertEquals(1, result.schedules[0].times.size)
            assertEquals(1, result.schedules[1].times.size)
        }

        @Test
        @DisplayName("동일 날짜의 복수 회차는 시작 시각 오름차순으로 정렬된다")
        fun sameDateMultipleTimesOrdered() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val scheduleId2 = UUID.randomUUID()
            val scheduleEarly = createSchedule(event) // now.plusDays(30) 이른 시각
            val scheduleLate = EventSchedule(
                id = scheduleId2, event = event, playSequence = 2,
                eventStartAt = now.plusDays(30).plusHours(3), // 같은 날, 늦은 시각
                eventEndAt = now.plusDays(30).plusHours(5),
                saleStartAt = now.plusDays(1), saleEndAt = now.plusDays(29),
                createdAt = now, updatedAt = now
            )

            every { eventRepository.findEventWithVenueAndHall(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(scheduleLate, scheduleEarly)
            every { eventRepository.findScheduleIdsWithAvailableSeats(any()) } returns setOf(scheduleId, scheduleId2)

            val result = eventService.getEvent(eventId)

            assertEquals(1, result.schedules.size)
            assertEquals(2, result.schedules[0].times.size)
            assertTrue(
                result.schedules[0].times[0].eventStartAt.isBefore(result.schedules[0].times[1].eventStartAt)
            )
        }

        @Test
        @DisplayName("AVAILABLE 좌석이 없는 회차는 isSoldOut이 true이다")
        fun soldOut() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event)

            every { eventRepository.findEventWithVenueAndHall(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(schedule)
            every { eventRepository.findScheduleIdsWithAvailableSeats(any()) } returns emptySet()

            val result = eventService.getEvent(eventId)

            assertTrue(result.schedules[0].times[0].isSoldOut)
        }
    }

    @Nested
    @DisplayName("updateEvent")
    inner class UpdateEvent {

        @Test
        @DisplayName("판매 전에는 모든 필드를 수정할 수 있다")
        fun successBeforeSale() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, saleStartAt = now.plusDays(1)) // 판매 시작 전

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(schedule)

            val result = eventService.updateEvent(eventId, EventDto.UpdateRequest(title = "BTS 투어 2026", artist = "BTS 팀"))

            assertEquals("BTS 투어 2026", result.title)
            assertEquals("BTS 팀", result.artist)
        }

        @Test
        @DisplayName("판매 시작 후 artist 수정 시 409 예외가 발생한다")
        fun artistNotModifiableAfterSale() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, saleStartAt = now.minusDays(1)) // 판매 이미 시작됨

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(schedule)

            val exception = assertThrows<EventException> {
                eventService.updateEvent(eventId, EventDto.UpdateRequest(artist = "변경된 아티스트"))
            }
            assertEquals(ErrorCode.EVENT_NOT_MODIFIABLE, exception.errorCode)
        }

        @Test
        @DisplayName("판매 시작 후에도 title과 description은 수정 가능하다")
        fun titleModifiableAfterSale() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)
            val schedule = createSchedule(event, saleStartAt = now.minusDays(1))

            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns event
            every { eventScheduleRepository.findByEventIdOrderByPlaySequence(eventId) } returns listOf(schedule)

            val result = eventService.updateEvent(
                eventId,
                EventDto.UpdateRequest(title = "BTS 투어 업데이트", description = "공연 설명 변경")
            )

            assertEquals("BTS 투어 업데이트", result.title)
        }

        @Test
        @DisplayName("존재하지 않는 공연 수정 시 예외가 발생한다")
        fun notFound() {
            every { eventRepository.findByIdAndDeletedAtIsNull(eventId) } returns null

            val exception = assertThrows<EventException> {
                eventService.updateEvent(eventId, EventDto.UpdateRequest(title = "새 제목"))
            }
            assertEquals(ErrorCode.EVENT_NOT_FOUND, exception.errorCode)
        }
    }

    @Nested
    @DisplayName("deleteEvent")
    inner class DeleteEvent {

        @Test
        @DisplayName("성공적으로 공연을 Soft Delete한다")
        fun success() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            every { eventRepository.findById(eventId) } returns Optional.of(event)
            every { seatRepository.existsByEventIdAndStatus(eventId, SeatStatus.SOLD) } returns false

            val result = eventService.deleteEvent(eventId)

            assertNotNull(result.message)
            assertNotNull(event.deletedAt) // soft delete로 deletedAt이 채워졌는지 확인
        }

        @Test
        @DisplayName("판매된 좌석이 있으면 삭제할 수 없다")
        fun hasSoldSeats() {
            val venue = createVenue()
            val hall = createHall(venue)
            val event = createEvent(venue, hall)

            every { eventRepository.findById(eventId) } returns Optional.of(event)
            every { seatRepository.existsByEventIdAndStatus(eventId, SeatStatus.SOLD) } returns true

            val exception = assertThrows<EventException> { eventService.deleteEvent(eventId) }
            assertEquals(ErrorCode.EVENT_HAS_RESERVATIONS, exception.errorCode)
        }

        @Test
        @DisplayName("존재하지 않는 공연 삭제 시 예외가 발생한다")
        fun notFound() {
            every { eventRepository.findById(eventId) } returns Optional.empty()

            val exception = assertThrows<EventException> { eventService.deleteEvent(eventId) }
            assertEquals(ErrorCode.EVENT_NOT_FOUND, exception.errorCode)
        }

        @Test
        @DisplayName("이미 삭제된 공연은 EVENT_ALREADY_DELETED 예외가 발생한다")
        fun alreadyDeleted() {
            val venue = createVenue()
            val hall = createHall(venue)
            val deletedEvent = createEvent(venue, hall).apply { softDelete() }

            every { eventRepository.findById(eventId) } returns Optional.of(deletedEvent)

            val exception = assertThrows<EventException> { eventService.deleteEvent(eventId) }
            assertEquals(ErrorCode.EVENT_ALREADY_DELETED, exception.errorCode)
        }
    }
}
