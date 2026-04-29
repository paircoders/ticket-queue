package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.SeatService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class InternalSeatControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var seatService: SeatService
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        seatService = mockk()
        val controller = InternalSeatController(seatService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("GET /internal/seats/status/{scheduleId}")
    inner class GetSoldSeatIds {

        @Test
        @DisplayName("200 OK - SOLD 좌석 ID 목록을 반환한다")
        fun success() {
            val soldId = UUID.randomUUID()
            val response = SeatDto.SoldSeatsResponse(
                scheduleId = scheduleId,
                soldSeatIds = listOf(soldId),
                totalSeats = 1
            )
            every { seatService.getSoldSeatIds(scheduleId) } returns response

            mockMvc.perform(get("/internal/seats/status/{scheduleId}", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.scheduleId").value(scheduleId.toString()))
                .andExpect(jsonPath("$.soldSeatIds[0]").value(soldId.toString()))
        }

        @Test
        @DisplayName("200 OK - SOLD 좌석 없으면 빈 리스트를 반환한다")
        fun noSoldSeats() {
            val response = SeatDto.SoldSeatsResponse(
                scheduleId = scheduleId,
                soldSeatIds = emptyList(),
                totalSeats = 0
            )
            every { seatService.getSoldSeatIds(scheduleId) } returns response

            mockMvc.perform(get("/internal/seats/status/{scheduleId}", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.soldSeatIds").isArray)
                .andExpect(jsonPath("$.soldSeatIds").isEmpty)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 scheduleId")
        fun notFound() {
            every { seatService.getSoldSeatIds(scheduleId) } throws EventException(ErrorCode.SCHEDULE_NOT_FOUND)

            mockMvc.perform(get("/internal/seats/status/{scheduleId}", scheduleId))
                .andExpect(status().isNotFound)
        }
    }
}
