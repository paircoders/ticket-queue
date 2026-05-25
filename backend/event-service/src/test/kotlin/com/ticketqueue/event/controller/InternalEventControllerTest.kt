package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.dto.EventDto
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.EventService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.MethodValidationInterceptor
import java.util.UUID

/**
 * 내부 공연 API 컨트롤러 단위 테스트
 *
 * @Validated + @Size 검증을 standalone MockMvc 에서 동작시키기 위해
 * ProxyFactory + MethodValidationInterceptor 로 컨트롤러를 AOP 래핑한다.
 * (실제 Spring 컨테이너의 MethodValidationPostProcessor 와 동일한 효과)
 */
class InternalEventControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var eventService: EventService
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val eventId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        eventService = mockk()
        val rawController = InternalEventController(eventService)
        // @Validated + @Size 동작을 위해 MethodValidationInterceptor 로 AOP 프록시 래핑
        val proxyFactory = ProxyFactory(rawController).apply {
            addAdvice(MethodValidationInterceptor(Validation.buildDefaultValidatorFactory().validator))
        }
        val controller = proxyFactory.proxy as InternalEventController

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Nested
    @DisplayName("GET /internal/events/{eventId}/info")
    inner class GetEventInfo {

        @Test
        @DisplayName("200 OK - 공연 핵심 정보를 반환한다")
        fun success() {
            val response = EventDto.EventInfoResponse(
                eventId = eventId,
                title = "BTS World Tour",
                artist = "BTS",
                venueName = "올림픽공원",
                hallName = "KSPO DOME"
            )
            every { eventService.getEventInfo(eventId) } returns response

            mockMvc.perform(get("/internal/events/{eventId}/info", eventId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.title").value("BTS World Tour"))
                .andExpect(jsonPath("$.artist").value("BTS"))
                .andExpect(jsonPath("$.venueName").value("올림픽공원"))
                .andExpect(jsonPath("$.hallName").value("KSPO DOME"))

            verify { eventService.getEventInfo(eventId) }
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 eventId 는 EVENT_NOT_FOUND 를 반환한다")
        fun notFound() {
            every { eventService.getEventInfo(eventId) } throws EventException(ErrorCode.EVENT_NOT_FOUND)

            mockMvc.perform(get("/internal/events/{eventId}/info", eventId))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
        }
    }

    @Nested
    @DisplayName("GET /internal/events/batch")
    inner class GetEventInfoBatch {

        @Test
        @DisplayName("200 OK - 요청한 공연 메타 목록을 반환한다")
        fun success() {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            val response = EventDto.EventInfoBatchResponse(
                events = listOf(
                    EventDto.EventInfoResponse(id1, "BTS World Tour", "BTS", "올림픽공원", "KSPO DOME"),
                    EventDto.EventInfoResponse(id2, "BLACKPINK Concert", "BLACKPINK", "고척돔", "메인홀")
                )
            )
            every { eventService.getEventInfoBatch(listOf(id1, id2)) } returns response

            mockMvc.perform(get("/internal/events/batch").param("eventIds", id1.toString(), id2.toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].eventId").value(id1.toString()))
                .andExpect(jsonPath("$.events[0].title").value("BTS World Tour"))
                .andExpect(jsonPath("$.events[1].eventId").value(id2.toString()))
                .andExpect(jsonPath("$.events[1].artist").value("BLACKPINK"))
        }

        @Test
        @DisplayName("200 OK - 미존재 ID 는 응답에서 제외 (요청 size 와 응답 size 불일치 가능)")
        fun missingIdsExcluded() {
            val id1 = UUID.randomUUID()
            val missingId = UUID.randomUUID()
            val response = EventDto.EventInfoBatchResponse(
                events = listOf(
                    EventDto.EventInfoResponse(id1, "BTS World Tour", "BTS", "올림픽공원", "KSPO DOME")
                )
            )
            every { eventService.getEventInfoBatch(listOf(id1, missingId)) } returns response

            mockMvc.perform(get("/internal/events/batch").param("eventIds", id1.toString(), missingId.toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventId").value(id1.toString()))
        }

        @Test
        @DisplayName("400 Bad Request - eventIds 파라미터 누락 시 INVALID_INPUT")
        fun missingParameter() {
            mockMvc.perform(get("/internal/events/batch"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }

        @Test
        @DisplayName("400 Bad Request - eventIds size 가 100을 초과하면 INVALID_INPUT")
        fun sizeTooLarge() {
            val ids = (1..101).map { UUID.randomUUID().toString() }.toTypedArray()
            mockMvc.perform(get("/internal/events/batch").param("eventIds", *ids))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        }
    }
}
