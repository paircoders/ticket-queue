package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.ScheduleService
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

class InternalScheduleControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var scheduleService: ScheduleService
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        scheduleService = mockk()
        val controller = InternalScheduleController(scheduleService)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("GET /internal/schedules/{scheduleId}/sellable")
    inner class CheckSellable {

        @Test
        @DisplayName("200 OK - 판매 가능한 회차는 sellable=true를 반환한다")
        fun sellable() {
            every { scheduleService.checkSellable(scheduleId) } returns
                ScheduleDto.SellableResponse(sellable = true)

            mockMvc.perform(get("/internal/schedules/{scheduleId}/sellable", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.sellable").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist())
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 scheduleId는 SCHEDULE_NOT_FOUND를 반환한다")
        fun notFound() {
            every { scheduleService.checkSellable(scheduleId) } throws
                EventException(ErrorCode.SCHEDULE_NOT_FOUND)

            mockMvc.perform(get("/internal/schedules/{scheduleId}/sellable", scheduleId))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"))
        }

        @Test
        @DisplayName("200 OK - 판매 시작 전 회차는 sellable=false, reason=TICKET_SALE_NOT_STARTED")
        fun saleNotStarted() {
            every { scheduleService.checkSellable(scheduleId) } returns
                ScheduleDto.SellableResponse(sellable = false, reason = "TICKET_SALE_NOT_STARTED")

            mockMvc.perform(get("/internal/schedules/{scheduleId}/sellable", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.sellable").value(false))
                .andExpect(jsonPath("$.reason").value("TICKET_SALE_NOT_STARTED"))
        }

        @Test
        @DisplayName("200 OK - 판매 종료 회차는 sellable=false, reason=TICKET_SALE_ENDED")
        fun saleEnded() {
            every { scheduleService.checkSellable(scheduleId) } returns
                ScheduleDto.SellableResponse(sellable = false, reason = "TICKET_SALE_ENDED")

            mockMvc.perform(get("/internal/schedules/{scheduleId}/sellable", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.sellable").value(false))
                .andExpect(jsonPath("$.reason").value("TICKET_SALE_ENDED"))
        }

        @Test
        @DisplayName("200 OK - CANCELLED/ENDED 상태 회차는 sellable=false, reason=SCHEDULE_NOT_AVAILABLE")
        fun cancelledOrEnded() {
            every { scheduleService.checkSellable(scheduleId) } returns
                ScheduleDto.SellableResponse(sellable = false, reason = "SCHEDULE_NOT_AVAILABLE")

            mockMvc.perform(get("/internal/schedules/{scheduleId}/sellable", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.sellable").value(false))
                .andExpect(jsonPath("$.reason").value("SCHEDULE_NOT_AVAILABLE"))
        }
    }
}
