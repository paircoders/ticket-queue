package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.dto.SeatDto
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.entity.SeatStatus
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
import java.math.BigDecimal
import java.util.UUID

class SeatControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var seatService: SeatService
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        seatService = mockk()
        val controller = SeatController(seatService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("GET /events/schedules/{scheduleId}/seats")
    inner class GetSeats {

        @Test
        @DisplayName("200 OK - 등급별 좌석 목록을 반환한다")
        fun success() {
            val seatId = UUID.randomUUID()
            val response = SeatDto.SeatsResponse(
                scheduleId = scheduleId,
                grades = listOf(
                    SeatDto.GradeGroup(
                        grade = SeatGrade.VIP,
                        price = BigDecimal("150000"),
                        seats = listOf(
                            SeatDto.SeatInfo(
                                id = seatId,
                                seatNumber = "A-1",
                                grade = SeatGrade.VIP,
                                status = SeatStatus.AVAILABLE
                            )
                        )
                    )
                )
            )
            every { seatService.getSeats(scheduleId) } returns response

            mockMvc.perform(get("/events/schedules/{scheduleId}/seats", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.scheduleId").value(scheduleId.toString()))
                .andExpect(jsonPath("$.grades").isArray)
                .andExpect(jsonPath("$.grades[0].grade").value("VIP"))
                .andExpect(jsonPath("$.grades[0].seats[0].id").value(seatId.toString()))
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 scheduleId")
        fun scheduleNotFound() {
            every { seatService.getSeats(scheduleId) } throws EventException(ErrorCode.SCHEDULE_NOT_FOUND)

            mockMvc.perform(get("/events/schedules/{scheduleId}/seats", scheduleId))
                .andExpect(status().isNotFound)
        }
    }
}
