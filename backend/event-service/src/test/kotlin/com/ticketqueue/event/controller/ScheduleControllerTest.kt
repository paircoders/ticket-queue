package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.config.SecurityConfig
import com.ticketqueue.event.dto.ScheduleDto
import com.ticketqueue.event.entity.ScheduleStatus
import com.ticketqueue.event.entity.SeatGrade
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.ScheduleService
import io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreAutoConfiguration
import io.awspring.cloud.autoconfigure.config.secretsmanager.SecretsManagerAutoConfiguration
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.junit.jupiter.api.DisplayName
import org.springframework.http.HttpStatus
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@WebMvcTest(
    controllers = [ScheduleController::class],
    useDefaultFilters = false,
    includeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [ScheduleController::class, GlobalExceptionHandler::class, SecurityConfig::class]
        )
    ],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        RedisAutoConfiguration::class,
        SecretsManagerAutoConfiguration::class,
        ParameterStoreAutoConfiguration::class
    ]
)
@ContextConfiguration(classes = [ScheduleController::class, GlobalExceptionHandler::class, SecurityConfig::class])
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("ScheduleController 단위 테스트")
class ScheduleControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var scheduleService: ScheduleService

    private val eventId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val now = LocalDateTime.now()

    private fun createResponse() = ScheduleDto.CreateResponse(
        id = scheduleId,
        eventId = eventId,
        playSequence = 1,
        eventStartAt = now.plusDays(30),
        eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = now.plusDays(1),
        saleEndAt = now.plusDays(29),
        status = ScheduleStatus.UPCOMING,
        createdAt = now
    )

    private fun listResponse(playSequence: Int = 1, isSoldOut: Boolean = false) = ScheduleDto.ListResponse(
        id = scheduleId,
        playSequence = playSequence,
        eventStartAt = now.plusDays(30),
        eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = now.plusDays(1),
        saleEndAt = now.plusDays(29),
        status = ScheduleStatus.UPCOMING,
        isSoldOut = isSoldOut
    )

    private fun detailResponse() = ScheduleDto.DetailResponse(
        id = scheduleId,
        eventId = eventId,
        eventTitle = "BTS World Tour",
        playSequence = 1,
        eventStartAt = now.plusDays(30),
        eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = now.plusDays(1),
        saleEndAt = now.plusDays(29),
        status = ScheduleStatus.UPCOMING,
        isSoldOut = false,
        createdAt = now,
        updatedAt = now
    )

    private fun changeStatusResponse() = ScheduleDto.ChangeStatusResponse(
        id = scheduleId,
        previousStatus = ScheduleStatus.UPCOMING,
        currentStatus = ScheduleStatus.ONGOING,
        updatedAt = now
    )

    private fun validCreateRequest() = ScheduleDto.CreateRequest(
        playSequence = 1,
        eventStartAt = now.plusDays(30),
        eventEndAt = now.plusDays(30).plusHours(2),
        saleStartAt = now.plusDays(1),
        saleEndAt = now.plusDays(29),
        priceByGrade = mapOf(SeatGrade.VIP to BigDecimal("100000"))
    )

    @Nested
    @DisplayName("POST /events/{eventId}/schedules")
    inner class CreateSchedule {

        @Test
        @DisplayName("201 Created - 회차를 생성한다")
        fun created() {
            every { scheduleService.createSchedule(eventId, any()) } returns createResponse()

            mockMvc.perform(
                post("/events/{eventId}/schedules", eventId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validCreateRequest()))
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").value(scheduleId.toString()))
                .andExpect(jsonPath("$.playSequence").value(1))
                .andExpect(jsonPath("$.status").value("UPCOMING"))
        }

        @Test
        @DisplayName("400 Bad Request - Bean Validation 실패 (playSequence < 1)")
        fun badRequestValidation() {
            val invalidRequest = mapOf(
                "playSequence" to 0, // Min(1) 위반
                "priceByGrade" to mapOf("VIP" to "100000")
            )

            mockMvc.perform(
                post("/events/{eventId}/schedules", eventId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("400 Bad Request - priceByGrade 누락")
        fun badRequestMissingPriceByGrade() {
            val invalidRequest = mapOf(
                "playSequence" to 1
                // priceByGrade 누락
            )

            mockMvc.perform(
                post("/events/{eventId}/schedules", eventId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연")
        fun eventNotFound() {
            every { scheduleService.createSchedule(eventId, any()) } throws EventException(ErrorCode.EVENT_NOT_FOUND)

            mockMvc.perform(
                post("/events/{eventId}/schedules", eventId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validCreateRequest()))
            )
                .andExpect(status().isNotFound)
        }

        @Test
        @DisplayName("409 Conflict - 회차 순번 중복")
        fun duplicatePlaySequence() {
            every { scheduleService.createSchedule(eventId, any()) } throws EventException(ErrorCode.DUPLICATE_PLAY_SEQUENCE)

            mockMvc.perform(
                post("/events/{eventId}/schedules", eventId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validCreateRequest()))
            )
                .andExpect(status().isConflict)
        }

        @Test
        @DisplayName("400 Bad Request - 회차 시간 유효성 검사 실패")
        fun invalidScheduleTime() {
            every { scheduleService.createSchedule(eventId, any()) } throws EventException(ErrorCode.INVALID_SCHEDULE_TIME)

            mockMvc.perform(
                post("/events/{eventId}/schedules", eventId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(validCreateRequest()))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_TIME"))
        }
    }

    @Nested
    @DisplayName("GET /events/{eventId}/schedules")
    inner class GetSchedules {

        @Test
        @DisplayName("200 OK - 회차 목록을 반환한다")
        fun list() {
            every { scheduleService.getSchedules(eventId) } returns listOf(listResponse())

            mockMvc.perform(get("/events/{eventId}/schedules", eventId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].playSequence").value(1))
                .andExpect(jsonPath("$[0].isSoldOut").value(false))
        }

        @Test
        @DisplayName("200 OK - 빈 목록을 반환한다")
        fun emptyList() {
            every { scheduleService.getSchedules(eventId) } returns emptyList<ScheduleDto.ListResponse>()

            mockMvc.perform(get("/events/{eventId}/schedules", eventId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
                .andExpect(jsonPath("$").isEmpty)
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연")
        fun eventNotFound() {
            every { scheduleService.getSchedules(eventId) } throws EventException(ErrorCode.EVENT_NOT_FOUND)

            mockMvc.perform(get("/events/{eventId}/schedules", eventId))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("GET /events/schedules/{scheduleId}")
    inner class GetSchedule {

        @Test
        @DisplayName("200 OK - 회차 상세를 반환한다")
        fun detail() {
            every { scheduleService.getSchedule(scheduleId) } returns detailResponse()

            mockMvc.perform(get("/events/schedules/{scheduleId}", scheduleId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(scheduleId.toString()))
                .andExpect(jsonPath("$.eventTitle").value("BTS World Tour"))
                .andExpect(jsonPath("$.isSoldOut").value(false))
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 회차")
        fun notFound() {
            every { scheduleService.getSchedule(scheduleId) } throws EventException(ErrorCode.SCHEDULE_NOT_FOUND)

            mockMvc.perform(get("/events/schedules/{scheduleId}", scheduleId))
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("PATCH /events/schedules/{scheduleId}/status")
    inner class ChangeScheduleStatus {

        @Test
        @DisplayName("200 OK - 회차 상태를 변경한다")
        fun success() {
            every { scheduleService.changeScheduleStatus(scheduleId, any()) } returns changeStatusResponse()

            mockMvc.perform(
                patch("/events/schedules/{scheduleId}/status", scheduleId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ScheduleDto.ChangeStatusRequest(ScheduleStatus.ONGOING)))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.previousStatus").value("UPCOMING"))
                .andExpect(jsonPath("$.currentStatus").value("ONGOING"))
        }

        @Test
        @DisplayName("400 Bad Request - status 누락")
        fun badRequestMissingStatus() {
            mockMvc.perform(
                patch("/events/schedules/{scheduleId}/status", scheduleId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("400 Bad Request - 유효하지 않은 상태 전이")
        fun invalidTransition() {
            every { scheduleService.changeScheduleStatus(scheduleId, any()) } throws EventException(ErrorCode.INVALID_SCHEDULE_STATUS)

            mockMvc.perform(
                patch("/events/schedules/{scheduleId}/status", scheduleId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ScheduleDto.ChangeStatusRequest(ScheduleStatus.UPCOMING)))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_STATUS"))
        }

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 회차")
        fun notFound() {
            every { scheduleService.changeScheduleStatus(scheduleId, any()) } throws EventException(ErrorCode.SCHEDULE_NOT_FOUND)

            mockMvc.perform(
                patch("/events/schedules/{scheduleId}/status", scheduleId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ScheduleDto.ChangeStatusRequest(ScheduleStatus.ONGOING)))
            )
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    @DisplayName("Security")
    inner class Security {

        @Nested
        @DisplayName("인증 없이 요청 시 401")
        inner class Unauthenticated {

            @Test
            @DisplayName("POST /events/{id}/schedules → 401")
            fun postSchedule_shouldReturn401_whenUnauthenticated() {
                val response = mockMvc.perform(
                    post("/events/{eventId}/schedules", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                ).andReturn().response
                response.status.shouldBe(HttpStatus.UNAUTHORIZED.value())
                objectMapper.readTree(response.contentAsString)["code"].asText().shouldBe("UNAUTHORIZED")
            }

            @Test
            @DisplayName("PATCH /events/schedules/{id}/status → 401")
            fun patchScheduleStatus_shouldReturn401_whenUnauthenticated() {
                val response = mockMvc.perform(
                    patch("/events/schedules/{scheduleId}/status", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                ).andReturn().response
                response.status.shouldBe(HttpStatus.UNAUTHORIZED.value())
                objectMapper.readTree(response.contentAsString)["code"].asText().shouldBe("UNAUTHORIZED")
            }
        }

        @Nested
        @DisplayName("ROLE_USER 요청 시 403")
        inner class Forbidden {

            @Test
            @DisplayName("POST /events/{id}/schedules with ROLE_USER → 403")
            fun postSchedule_shouldReturnForbidden_whenUserRole() {
                mockMvc.perform(
                    post("/events/{eventId}/schedules", eventId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                )
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }

            @Test
            @DisplayName("PATCH /events/schedules/{id}/status with ROLE_USER → 403")
            fun patchScheduleStatus_shouldReturnForbidden_whenUserRole() {
                mockMvc.perform(
                    patch("/events/schedules/{scheduleId}/status", scheduleId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                )
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }

        @Nested
        @DisplayName("인증 없이 공개 엔드포인트 200")
        inner class PublicAccess {

            @Test
            @DisplayName("GET /events/{id}/schedules → 200")
            fun getSchedules_shouldReturnOk_whenNoAuth() {
                every { scheduleService.getSchedules(eventId) } returns listOf(listResponse())

                mockMvc.perform(get("/events/{eventId}/schedules", eventId))
                    .andExpect(status().isOk)
            }

            @Test
            @DisplayName("GET /events/schedules/{id} → 200")
            fun getSchedule_shouldReturnOk_whenNoAuth() {
                every { scheduleService.getSchedule(scheduleId) } returns detailResponse()

                mockMvc.perform(get("/events/schedules/{scheduleId}", scheduleId))
                    .andExpect(status().isOk)
            }
        }
    }
}
