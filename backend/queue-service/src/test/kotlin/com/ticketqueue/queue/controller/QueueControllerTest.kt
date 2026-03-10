package com.ticketqueue.queue.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.queue.config.SecurityConfig
import com.ticketqueue.queue.dto.QueueDto
import com.ticketqueue.queue.dto.QueueStatus
import com.ticketqueue.queue.exception.QueueException
import com.ticketqueue.queue.service.QueueService
import io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreAutoConfiguration
import io.awspring.cloud.autoconfigure.config.secretsmanager.SecretsManagerAutoConfiguration
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [QueueController::class],
    useDefaultFilters = false,
    includeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [QueueController::class, GlobalExceptionHandler::class, SecurityConfig::class]
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
@ContextConfiguration(classes = [QueueController::class, GlobalExceptionHandler::class, SecurityConfig::class])
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("QueueController 단위 테스트")
class QueueControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var queueService: QueueService

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    @Nested
    @DisplayName("GET /queue/status")
    inner class GetQueueStatus {

        @Test
        @DisplayName("200 OK - WAITING 상태를 반환한다")
        fun returnsWaitingStatus() {
            val response = QueueDto.StatusResponse(
                status = QueueStatus.WAITING,
                rank = 42L,
                estimatedWaitTime = 5L,
                token = null
            )
            every { queueService.getQueueStatus(any(), scheduleId) } returns response

            mockMvc.perform(
                get("/queue/status")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", scheduleId.toString())
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.rank").value(42))
                .andExpect(jsonPath("$.estimatedWaitTime").value(5))
                .andExpect(jsonPath("$.token").doesNotExist())
        }

        @Test
        @DisplayName("200 OK - ACTIVE 상태와 토큰을 반환한다")
        fun returnsActiveStatusWithToken() {
            val token = "queue-token-xyz789"
            val response = QueueDto.StatusResponse(
                status = QueueStatus.ACTIVE,
                rank = 0L,
                estimatedWaitTime = 0L,
                token = token
            )
            every { queueService.getQueueStatus(any(), scheduleId) } returns response

            mockMvc.perform(
                get("/queue/status")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", scheduleId.toString())
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rank").value(0))
                .andExpect(jsonPath("$.estimatedWaitTime").value(0))
                .andExpect(jsonPath("$.token").value(token))
        }

        @Test
        @DisplayName("404 Not Found - 대기열에 없는 사용자")
        fun returnsNotFoundWhenNotInQueue() {
            every { queueService.getQueueStatus(any(), scheduleId) } throws QueueException(ErrorCode.NOT_IN_QUEUE)

            mockMvc.perform(
                get("/queue/status")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", scheduleId.toString())
            )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOT_IN_QUEUE"))
        }

        @Test
        @DisplayName("429 Too Many Requests - Rate Limit 초과")
        fun returnsTooManyRequestsWhenRateLimitExceeded() {
            every { queueService.getQueueStatus(any(), scheduleId) } throws QueueException(ErrorCode.RATE_LIMIT_EXCEEDED)

            mockMvc.perform(
                get("/queue/status")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", scheduleId.toString())
            )
                .andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
        }

        @Test
        @DisplayName("400 Bad Request - scheduleId 파라미터 누락")
        fun returnsBadRequestWhenScheduleIdMissing() {
            mockMvc.perform(
                get("/queue/status")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("400 Bad Request - scheduleId가 유효하지 않은 UUID 형식")
        fun returnsBadRequestWhenScheduleIdInvalidUuid() {
            mockMvc.perform(
                get("/queue/status")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", "not-a-uuid")
            )
                .andExpect(status().isBadRequest)
        }

        @Nested
        @DisplayName("Security")
        inner class Security {

            @Test
            @DisplayName("401 Unauthorized - 인증 헤더 없이 요청 시")
            fun returnsUnauthorizedWhenNoAuthHeader() {
                val response = mockMvc.perform(
                    get("/queue/status")
                        .param("scheduleId", scheduleId.toString())
                ).andReturn().response

                response.status.shouldBe(HttpStatus.UNAUTHORIZED.value())
                objectMapper.readTree(response.contentAsString)["code"].asText().shouldBe("UNAUTHORIZED")
            }
        }
    }

    @Nested
    @DisplayName("DELETE /queue/leave")
    inner class DeleteQueueLeave {

        @Test
        @DisplayName("200 OK - 대기열 이탈 성공")
        fun returnsOkOnLeaveSuccess() {
            every { queueService.leaveQueue(any(), scheduleId) } returns QueueDto.LeaveResponse("Removed from queue")

            mockMvc.perform(
                delete("/queue/leave")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", scheduleId.toString())
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("Removed from queue"))
        }

        @Test
        @DisplayName("404 Not Found - 대기열에 없는 사용자")
        fun returnsNotFoundWhenNotInQueue() {
            every { queueService.leaveQueue(any(), scheduleId) } throws QueueException(ErrorCode.NOT_IN_QUEUE)

            mockMvc.perform(
                delete("/queue/leave")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", scheduleId.toString())
            )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOT_IN_QUEUE"))
        }

        @Test
        @DisplayName("400 Bad Request - scheduleId 파라미터 누락")
        fun returnsBadRequestWhenScheduleIdMissing() {
            mockMvc.perform(
                delete("/queue/leave")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("400 Bad Request - 유효하지 않은 UUID 형식")
        fun returnsBadRequestWhenScheduleIdInvalidUuid() {
            mockMvc.perform(
                delete("/queue/leave")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", "not-a-uuid")
            )
                .andExpect(status().isBadRequest)
        }

        @Nested
        @DisplayName("Security")
        inner class Security {

            @Test
            @DisplayName("401 Unauthorized - 인증 헤더 없이 요청")
            fun returnsUnauthorizedWhenNoAuthHeader() {
                val response = mockMvc.perform(
                    delete("/queue/leave")
                        .param("scheduleId", scheduleId.toString())
                ).andReturn().response

                response.status.shouldBe(HttpStatus.UNAUTHORIZED.value())
                objectMapper.readTree(response.contentAsString)["code"].asText().shouldBe("UNAUTHORIZED")
            }
        }
    }
}
