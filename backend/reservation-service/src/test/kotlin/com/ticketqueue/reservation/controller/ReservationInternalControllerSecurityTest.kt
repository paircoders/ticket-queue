package com.ticketqueue.reservation.controller

import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.mockk.every
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.config.import=",
        "spring.cloud.aws.region.static=us-east-1",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "spring.cloud.aws.secretsmanager.enabled=false"
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("ReservationInternalController 보안 통합 테스트")
class ReservationInternalControllerSecurityTest {

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ticketing")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/init.sql")

        @Container @JvmStatic
        val valkey = GenericContainer("valkey/valkey:8.1.5-alpine3.23")
            .withExposedPorts(6379)

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(6379) }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @MockkBean private lateinit var eventServiceClient: EventServiceClient
    @MockkBean private lateinit var reservationRepository: ReservationRepository
    @MockkBean private lateinit var reservationSeatRepository: ReservationSeatRepository

    private val reservationId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    @Test
    @DisplayName("X-Service-Api-Key 헤더 누락 시 401 반환")
    fun missingApiKey() {
        mockMvc.perform(get("/internal/reservations/$reservationId"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("잘못된 X-Service-Api-Key 시 401 반환")
    fun wrongApiKey() {
        mockMvc.perform(
            get("/internal/reservations/$reservationId")
                .header("X-Service-Api-Key", "wrong-key")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("올바른 X-Service-Api-Key 시 인증을 통과하고 200 반환")
    fun correctApiKey() {
        every { reservationRepository.findById(reservationId) } returns Optional.of(
            Reservation(
                id = reservationId,
                userId = userId,
                scheduleId = scheduleId,
                eventId = UUID.randomUUID(),
                totalAmount = BigDecimal("300000"),
                holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
            )
        )
        every { reservationSeatRepository.findByReservationId(reservationId) } returns emptyList()

        mockMvc.perform(
            get("/internal/reservations/$reservationId")
                .header("X-Service-Api-Key", "test-internal-api-key")
        ).andExpect(status().isOk)
    }
}
