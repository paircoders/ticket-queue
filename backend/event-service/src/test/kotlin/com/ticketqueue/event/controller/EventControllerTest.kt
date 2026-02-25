package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.entity.EventStatus
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.EventService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class EventControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var eventService: EventService
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val eventId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private fun createResponse() = EventDto.CreateResponse(
        id = eventId,
        title = "BTS World Tour",
        artist = "BTS",
        status = EventStatus.PREPARING,
        createdAt = now
    )

    private fun listResponse() = EventDto.ListResponse(
        id = eventId,
        title = "BTS World Tour",
        artist = "BTS",
        venueName = "올림픽공원",
        startDate = now.plusDays(30),
        endDate = now.plusDays(31),
        status = EventStatus.PREPARING
    )

    private fun detailResponse() = EventDto.DetailResponse(
        id = eventId,
        title = "BTS World Tour",
        artist = "BTS",
        description = null,
        venueId = UUID.randomUUID(),
        venueName = "올림픽공원",
        hallId = UUID.randomUUID(),
        hallName = "KSPO DOME",
        status = EventStatus.PREPARING,
        schedules = emptyList(),
        createdAt = now,
        updatedAt = now
    )

    private fun updateResponse() = EventDto.UpdateResponse(
        id = eventId,
        title = "BTS World Tour 2026",
        artist = "BTS",
        description = null,
        status = EventStatus.PREPARING,
        updatedAt = now
    )

    private fun validCreateRequest() = EventDto.CreateRequest(
        title = "BTS World Tour",
        artist = "BTS",
        venueId = UUID.randomUUID(),
        hallId = UUID.randomUUID(),
        priceByGrade = mapOf(SeatGrade.VIP to BigDecimal("100000")),
        schedules = listOf(
            EventDto.ScheduleRequest(
                playSequence = 1,
                eventStartAt = now.plusDays(30),
                eventEndAt = now.plusDays(30).plusHours(2),
                saleStartAt = now.plusDays(1),
                saleEndAt = now.plusDays(29)
            )
        )
    )

    @BeforeEach
    fun setUp() {
        eventService = mockk()
        val controller = EventController(eventService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("POST /events")
    inner class CreateEvent {

        @Test
        @DisplayName("201 Created - 공연을 생성한다")
        fun created() {
            every { eventService.createEvent(any()) } returns createResponse()

            mockMvc.perform(
                post("/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validCreateRequest()))
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.title").value("BTS World Tour"))
                .andExpect(jsonPath("$.artist").value("BTS"))
                .andExpect(jsonPath("$.status").value("PREPARING"))
        }

        @Test
        @DisplayName("400 Bad Request - 유효성 검사 실패 (title 누락)")
        fun badRequest() {
            val invalidRequest = mapOf("artist" to "BTS") // title, venueId, hallId 등 누락

            mockMvc.perform(
                post("/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
        }
    }

    @Nested
    @DisplayName("GET /events")
    inner class GetEvents {

        @Test
        @DisplayName("200 OK - 공연 목록을 조회한다")
        fun list() {
            val pageable = PageRequest.of(0, 20)
            val page = PageImpl(listOf(listResponse()), pageable, 1)
            every { eventService.getEvents(0, 20, null, null, null) } returns page

            mockMvc.perform(get("/events"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.list[0].title").value("BTS World Tour"))
                .andExpect(jsonPath("$.totalElements").value(1))
        }

        @Test
        @DisplayName("200 OK - 검색어와 도시 필터로 공연 목록을 조회한다")
        fun listWithFilters() {
            val pageable = PageRequest.of(0, 20)
            val page = PageImpl(listOf(listResponse()), pageable, 1)
            every { eventService.getEvents(0, 20, null, "서울", "BTS") } returns page

            mockMvc.perform(get("/events").param("city", "서울").param("keyword", "BTS"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.list[0].artist").value("BTS"))
        }
    }

    @Nested
    @DisplayName("GET /events/{eventId}")
    inner class GetEvent {

        @Test
        @DisplayName("200 OK - 공연 상세를 조회한다 (회차 날짜별 그룹핑)")
        fun detail() {
            every { eventService.getEvent(eventId) } returns detailResponse()

            mockMvc.perform(get("/events/{eventId}", eventId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.title").value("BTS World Tour"))
                .andExpect(jsonPath("$.venueName").value("올림픽공원"))
                .andExpect(jsonPath("$.schedules").isArray)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연")
        fun notFound() {
            every { eventService.getEvent(eventId) } throws EventException(ErrorCode.EVENT_NOT_FOUND)

            mockMvc.perform(get("/events/{eventId}", eventId))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("PATCH /events/{eventId}")
    inner class UpdateEvent {

        @Test
        @DisplayName("200 OK - 공연을 수정한다")
        fun success() {
            every { eventService.updateEvent(eventId, any()) } returns updateResponse()

            mockMvc.perform(
                patch("/events/{eventId}", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(EventDto.UpdateRequest(title = "BTS World Tour 2026")))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.title").value("BTS World Tour 2026"))
        }

        @Test
        @DisplayName("409 Conflict - 판매 시작 후 artist 수정 불가")
        fun artistNotModifiable() {
            every { eventService.updateEvent(eventId, any()) } throws EventException(ErrorCode.EVENT_NOT_MODIFIABLE)

            mockMvc.perform(
                patch("/events/{eventId}", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(EventDto.UpdateRequest(artist = "변경된 아티스트")))
            )
                .andExpect(status().isConflict)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연 수정")
        fun notFound() {
            every { eventService.updateEvent(eventId, any()) } throws EventException(ErrorCode.EVENT_NOT_FOUND)

            mockMvc.perform(
                patch("/events/{eventId}", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(EventDto.UpdateRequest(title = "새 제목")))
            )
                .andExpect(status().isNotFound)
        }

        @Test
        @DisplayName("400 Bad Request - title 빈 문자열 전달 시 @Size(min=1) 검증 실패")
        fun titleEmpty() {
            mockMvc.perform(
                patch("/events/{eventId}", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(EventDto.UpdateRequest(title = "")))
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("200 OK - 빈 body 전달 시 nullable 필드 모두 null이므로 유효성 검사 통과")
        fun emptyBody() {
            every { eventService.updateEvent(eventId, any()) } returns updateResponse()

            mockMvc.perform(
                patch("/events/{eventId}", eventId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
                .andExpect(status().isOk)
        }
    }

    @Nested
    @DisplayName("DELETE /events/{eventId}")
    inner class DeleteEvent {

        @Test
        @DisplayName("200 OK - 공연을 Soft Delete한다")
        fun success() {
            every { eventService.deleteEvent(eventId) } returns EventDto.DeleteResponse("공연이 삭제되었습니다.")

            mockMvc.perform(delete("/events/{eventId}", eventId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("공연이 삭제되었습니다."))
        }

        @Test
        @DisplayName("409 Conflict - 판매된 좌석이 있는 공연 삭제 시")
        fun conflict() {
            every { eventService.deleteEvent(eventId) } throws EventException(ErrorCode.EVENT_HAS_RESERVATIONS)

            mockMvc.perform(delete("/events/{eventId}", eventId))
                .andExpect(status().isConflict)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연 삭제")
        fun notFound() {
            every { eventService.deleteEvent(eventId) } throws EventException(ErrorCode.EVENT_NOT_FOUND)

            mockMvc.perform(delete("/events/{eventId}", eventId))
                .andExpect(status().isNotFound)
        }
    }
}
