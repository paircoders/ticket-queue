package com.ticketqueue.event.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import com.ticketqueue.event.config.SecurityConfig
import com.ticketqueue.event.dto.HallDto
import com.ticketqueue.event.dto.SeatTemplateDto
import com.ticketqueue.event.exception.EventException
import com.ticketqueue.event.service.HallService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

@WebMvcTest(
    controllers = [HallController::class],
    useDefaultFilters = false,
    includeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [HallController::class, GlobalExceptionHandler::class, SecurityConfig::class]
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
@ContextConfiguration(classes = [HallController::class, GlobalExceptionHandler::class, SecurityConfig::class])
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("HallController 단위 테스트")
class HallControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var hallService: HallService

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
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
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
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
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
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
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
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
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

        @Test
        @DisplayName("404 Not Found - 존재하지 않는 공연장")
        fun venueNotFound() {
            every { hallService.getHalls(venueId) } throws EventException(ErrorCode.VENUE_NOT_FOUND)

            mockMvc.perform(get("/venues/{venueId}/halls", venueId))
                .andExpect(status().isNotFound)
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
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
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
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
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

            mockMvc.perform(
                delete("/venues/{venueId}/halls/{hallId}", venueId, hallId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.message").value("홀이 삭제되었습니다."))
        }

        @Test
        @DisplayName("409 Conflict - 공연이 존재하는 홀 삭제 시")
        fun conflict() {
            every { hallService.deleteHall(venueId, hallId) } throws
                EventException(ErrorCode.HALL_HAS_EVENTS)

            mockMvc.perform(
                delete("/venues/{venueId}/halls/{hallId}", venueId, hallId)
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Role", "ADMIN")
            )
                .andExpect(status().isConflict)
        }
    }

    @Nested
    @DisplayName("Security")
    inner class Security {

        @Nested
        @DisplayName("인증 없이 요청 시 401")
        inner class Unauthenticated {

            @Test
            @DisplayName("POST /venues/{id}/halls → 401")
            fun postHall_shouldReturnUnauthorized_whenNoAuth() {
                val response = mockMvc.perform(
                    post("/venues/{venueId}/halls", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                ).andReturn().response
                response.status.shouldBe(HttpStatus.UNAUTHORIZED.value())
                objectMapper.readTree(response.contentAsString)["code"].asText().shouldBe("UNAUTHORIZED")
            }

            @Test
            @DisplayName("PATCH /venues/{id}/halls/{id} → 401")
            fun patchHall_shouldReturnUnauthorized_whenNoAuth() {
                val response = mockMvc.perform(
                    patch("/venues/{venueId}/halls/{hallId}", venueId, hallId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                ).andReturn().response
                response.status.shouldBe(HttpStatus.UNAUTHORIZED.value())
                objectMapper.readTree(response.contentAsString)["code"].asText().shouldBe("UNAUTHORIZED")
            }

            @Test
            @DisplayName("DELETE /venues/{id}/halls/{id} → 401")
            fun deleteHall_shouldReturnUnauthorized_whenNoAuth() {
                val response = mockMvc.perform(delete("/venues/{venueId}/halls/{hallId}", venueId, hallId)).andReturn().response
                response.status.shouldBe(HttpStatus.UNAUTHORIZED.value())
                objectMapper.readTree(response.contentAsString)["code"].asText().shouldBe("UNAUTHORIZED")
            }
        }

        @Nested
        @DisplayName("ROLE_USER 요청 시 403")
        inner class Forbidden {

            @Test
            @DisplayName("POST /venues/{id}/halls with ROLE_USER → 403")
            fun `post halls with user role returns 403`() {
                mockMvc.perform(
                    post("/venues/{venueId}/halls", venueId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                )
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }

            @Test
            @DisplayName("PATCH /venues/{id}/halls/{id} with ROLE_USER → 403")
            fun `patch hall with user role returns 403`() {
                mockMvc.perform(
                    patch("/venues/{venueId}/halls/{hallId}", venueId, hallId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                )
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }

            @Test
            @DisplayName("DELETE /venues/{id}/halls/{id} with ROLE_USER → 403")
            fun `delete hall with user role returns 403`() {
                mockMvc.perform(
                    delete("/venues/{venueId}/halls/{hallId}", venueId, hallId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "USER")
                )
                    .andExpect(status().isForbidden)
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }

        @Nested
        @DisplayName("인증 없이 공개 엔드포인트 200")
        inner class PublicAccess {

            @Test
            @DisplayName("GET /venues/{id}/halls → 200")
            fun `get halls without auth returns 200`() {
                every { hallService.getHalls(venueId) } returns listOf(hallResponse())

                mockMvc.perform(get("/venues/{venueId}/halls", venueId))
                    .andExpect(status().isOk)
            }

            @Test
            @DisplayName("GET /venues/{id}/halls/{id} → 200")
            fun `get hall without auth returns 200`() {
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
            }
        }
    }
}
