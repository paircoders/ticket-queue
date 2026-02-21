package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.dto.HallDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.HallService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
import java.time.LocalDateTime
import java.util.UUID

class HallControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var hallService: HallService
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val venueId = UUID.randomUUID()
    private val hallId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private val seatTemplateDto = SeatTemplateDto(
        rows = listOf("A", "B", "C"),
        seatsPerRow = 10,
        gradeMapping = mapOf("A" to "VIP", "B" to "S", "C" to "A")
    )

    private fun hallResponse() = HallDto.Response(
        id = hallId,
        venueId = venueId,
        name = "KSPO DOME",
        capacity = 15000,
        createdAt = now
    )

    @BeforeEach
    fun setUp() {
        hallService = mockk()
        val controller = HallController(hallService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("POST /venues/{venueId}/halls")
    inner class CreateHall {

        @Test
        @DisplayName("201 Created - 관리자가 홀을 생성한다")
        fun created() {
            every { hallService.createHall(venueId, any()) } returns hallResponse()

            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = seatTemplateDto
            )
            mockMvc.perform(
                post("/venues/{venueId}/halls", venueId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("KSPO DOME"))
                .andExpect(jsonPath("$.capacity").value(15000))
        }

        @Test
        @DisplayName("400 Bad Request - seatTemplate 누락")
        fun badRequestSeatTemplate() {
            val request = mapOf(
                "name" to "KSPO DOME",
                "capacity" to 15000
            )

            mockMvc.perform(
                post("/venues/{venueId}/halls", venueId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("400 Bad Request - capacity가 0")
        fun badRequestCapacity() {
            val request = mapOf(
                "name" to "KSPO DOME",
                "capacity" to 0,
                "seatTemplate" to seatTemplateDto
            )

            mockMvc.perform(
                post("/venues/{venueId}/halls", venueId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연장")
        fun venueNotFound() {
            every { hallService.createHall(venueId, any()) } throws
                EventException(ErrorCode.VENUE_NOT_FOUND)

            val request = HallDto.CreateRequest(
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = seatTemplateDto
            )
            mockMvc.perform(
                post("/venues/{venueId}/halls", venueId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("GET /venues/{venueId}/halls")
    inner class GetHalls {

        @Test
        @DisplayName("200 OK - 홀 목록을 조회한다")
        fun list() {
            every { hallService.getHalls(venueId) } returns listOf(hallResponse())

            mockMvc.perform(get("/venues/{venueId}/halls", venueId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].name").value("KSPO DOME"))
        }
    }

    @Nested
    @DisplayName("GET /venues/{venueId}/halls/{hallId}")
    inner class GetHall {

        @Test
        @DisplayName("200 OK - 홀 상세를 조회한다")
        fun detail() {
            val detail = HallDto.DetailResponse(
                id = hallId,
                venueId = venueId,
                name = "KSPO DOME",
                capacity = 15000,
                seatTemplate = seatTemplateDto,
                createdAt = now,
                updatedAt = now
            )
            every { hallService.getHall(venueId, hallId) } returns detail

            mockMvc.perform(get("/venues/{venueId}/halls/{hallId}", venueId, hallId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("KSPO DOME"))
                .andExpect(jsonPath("$.seatTemplate.rows[0]").value("A"))
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 홀")
        fun notFound() {
            every { hallService.getHall(venueId, hallId) } throws EventException(ErrorCode.HALL_NOT_FOUND)

            mockMvc.perform(get("/venues/{venueId}/halls/{hallId}", venueId, hallId))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("PATCH /venues/{venueId}/halls/{hallId}")
    inner class UpdateHall {

        @Test
        @DisplayName("200 OK - 홀을 수정한다")
        fun success() {
            val response = HallDto.UpdateResponse(
                id = hallId,
                name = "올림픽홀",
                capacity = 15000,
                updatedAt = now
            )
            every { hallService.updateHall(venueId, hallId, any()) } returns response

            val request = HallDto.UpdateRequest(name = "올림픽홀")
            mockMvc.perform(
                patch("/venues/{venueId}/halls/{hallId}", venueId, hallId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("올림픽홀"))
        }

        @Test
        @DisplayName("409 Conflict - 이름 중복")
        fun conflict() {
            every { hallService.updateHall(venueId, hallId, any()) } throws
                EventException(ErrorCode.HALL_NAME_DUPLICATE)

            val request = HallDto.UpdateRequest(name = "올림픽홀")
            mockMvc.perform(
                patch("/venues/{venueId}/halls/{hallId}", venueId, hallId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
            )
                .andExpect(status().isConflict)
        }
    }

    @Nested
    @DisplayName("DELETE /venues/{venueId}/halls/{hallId}")
    inner class DeleteHall {

        @Test
        @DisplayName("200 OK - 홀을 삭제한다")
        fun success() {
            every { hallService.deleteHall(venueId, hallId) } returns
                HallDto.DeleteResponse("홀이 삭제되었습니다.")

            mockMvc.perform(delete("/venues/{venueId}/halls/{hallId}", venueId, hallId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("홀이 삭제되었습니다."))
        }

        @Test
        @DisplayName("409 Conflict - 공연이 존재하는 홀 삭제 시")
        fun conflict() {
            every { hallService.deleteHall(venueId, hallId) } throws
                EventException(ErrorCode.HALL_HAS_EVENTS)

            mockMvc.perform(delete("/venues/{venueId}/halls/{hallId}", venueId, hallId))
                .andExpect(status().isConflict)
        }
    }
}
