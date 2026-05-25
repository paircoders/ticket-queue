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
import jakarta.validation.Validation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.hamcrest.Matchers.emptyOrNullString
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.MethodValidationInterceptor
import java.time.LocalDateTime
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
        val rawController = InternalScheduleController(scheduleService)
        // @Validated + @Size 가 standalone MockMvc 에서 동작하도록 AOP 프록시 래핑
        val proxyFactory = ProxyFactory(rawController).apply {
            addAdvice(MethodValidationInterceptor(Validation.buildDefaultValidatorFactory().validator))
        }
        val controller = proxyFactory.proxy as InternalScheduleController
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("GET /internal/schedules/ended")
    inner class GetEndedScheduleIds {

        @Test
        @DisplayName("200 OK - 종료된 회차 ID 목록을 반환한다")
        fun success() {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            every { scheduleService.getCleanupTargetScheduleIds() } returns listOf(id1, id2)

            mockMvc.perform(get("/internal/schedules/ended"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.scheduleIds").isArray)
                .andExpect(jsonPath("$.scheduleIds.length()").value(2))
                .andExpect(jsonPath("$.scheduleIds[0]").value(id1.toString()))
                .andExpect(jsonPath("$.scheduleIds[1]").value(id2.toString()))
        }

        @Test
        @DisplayName("500 Internal Server Error - 서비스 예외 발생 시 에러 응답을 반환한다")
        fun serviceError() {
            every { scheduleService.getCleanupTargetScheduleIds() } throws RuntimeException("DB error")

            mockMvc.perform(get("/internal/schedules/ended"))
                .andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").isString)
                .andExpect(jsonPath("$.timestamp").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")))
                .andExpect(jsonPath("$.traceId").value(not(emptyOrNullString())))
        }

        @Test
        @DisplayName("200 OK - 종료된 회차가 없으면 빈 목록을 반환한다")
        fun empty() {
            every { scheduleService.getCleanupTargetScheduleIds() } returns emptyList()

            mockMvc.perform(get("/internal/schedules/ended"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.scheduleIds").isArray)
                .andExpect(jsonPath("$.scheduleIds.length()").value(0))
        }
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

    @Nested
    @DisplayName("GET /internal/schedules/batch")
    inner class GetScheduleInfoBatch {

        @Test
        @DisplayName("200 OK - 회차 핵심 정보 목록을 반환한다")
        fun success() {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            val eventIdFixture = UUID.randomUUID()
            val now = LocalDateTime.of(2026, 6, 1, 19, 0)
            val response = ScheduleDto.ScheduleInfoBatchResponse(
                schedules = listOf(
                    ScheduleDto.ScheduleInfoResponse(id1, eventIdFixture, now, now.plusHours(2), now.minusDays(10), now.minusHours(1)),
                    ScheduleDto.ScheduleInfoResponse(id2, eventIdFixture, now.plusDays(1), now.plusDays(1).plusHours(2), now.minusDays(10), now.plusDays(1).minusHours(1))
                )
            )
            every { scheduleService.getScheduleInfoBatch(listOf(id1, id2)) } returns response

            mockMvc.perform(get("/internal/schedules/batch").param("scheduleIds", id1.toString(), id2.toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.schedules.length()").value(2))
                .andExpect(jsonPath("$.schedules[0].scheduleId").value(id1.toString()))
                .andExpect(jsonPath("$.schedules[0].eventId").value(eventIdFixture.toString()))
                .andExpect(jsonPath("$.schedules[1].scheduleId").value(id2.toString()))
        }

        @Test
        @DisplayName("200 OK - 미존재 ID 는 응답에서 제외된다")
        fun missingIdsExcluded() {
            val id1 = UUID.randomUUID()
            val missingId = UUID.randomUUID()
            val eventIdFixture = UUID.randomUUID()
            val now = LocalDateTime.of(2026, 6, 1, 19, 0)
            val response = ScheduleDto.ScheduleInfoBatchResponse(
                schedules = listOf(
                    ScheduleDto.ScheduleInfoResponse(id1, eventIdFixture, now, now.plusHours(2), now.minusDays(10), now.minusHours(1))
                )
            )
            every { scheduleService.getScheduleInfoBatch(listOf(id1, missingId)) } returns response

            mockMvc.perform(get("/internal/schedules/batch").param("scheduleIds", id1.toString(), missingId.toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.schedules.length()").value(1))
                .andExpect(jsonPath("$.schedules[0].scheduleId").value(id1.toString()))
        }

        @Test
        @DisplayName("400 Bad Request - scheduleIds 파라미터 누락 시 INVALID_INPUT")
        fun missingParameter() {
            mockMvc.perform(get("/internal/schedules/batch"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }

        @Test
        @DisplayName("400 Bad Request - scheduleIds size 가 100을 초과하면 INVALID_INPUT")
        fun sizeTooLarge() {
            val ids = (1..101).map { UUID.randomUUID().toString() }.toTypedArray()
            mockMvc.perform(get("/internal/schedules/batch").param("scheduleIds", *ids))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }
    }
}
