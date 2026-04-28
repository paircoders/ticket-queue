package com.ticketqueue.reservation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import com.ticketqueue.reservation.client.EventServiceClient
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.config.import=",                             // aws-secretsmanager import 비활성화
        "spring.cloud.aws.region.static=us-east-1",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "spring.cloud.aws.secretsmanager.enabled=false"
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("좌석 선점 API 통합 테스트 (POST /reservations/hold)")
class HoldSeatsIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ticketing")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/init.sql")

        @Container
        @JvmStatic
        val valkey = GenericContainer("valkey/valkey:8.1.5-alpine3.23")
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(6379) }
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var stringRedisTemplate: StringRedisTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper

    @SpykBean private lateinit var redissonClient: RedissonClient
    @MockkBean private lateinit var eventServiceClient: EventServiceClient

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val seatId = UUID.randomUUID()
    private val validToken = "test-queue-token-${UUID.randomUUID()}"
    private lateinit var rLock: RLock
    private lateinit var multiLock: RLock

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()

        rLock = mockk()
        multiLock = mockk()
        every { redissonClient.getLock(any<String>()) } returns rLock
        every { redissonClient.getMultiLock(*varargAny { true }) } returns multiLock
        every { rLock.tryLock(any<Long>(), any<Long>(), any()) } returns true
        every { rLock.isHeldByCurrentThread } returns true
        justRun { rLock.unlock() }
        every { multiLock.tryLock(any<Long>(), any<Long>(), any()) } returns true
        every { multiLock.isHeldByCurrentThread } returns true
        justRun { multiLock.unlock() }

        every { eventServiceClient.getSoldSeats(scheduleId) } returns
            EventServiceClient.SoldSeatsResponse(scheduleId, emptyList())
        every { eventServiceClient.getSeatDetails(scheduleId, any()) } returns
            EventServiceClient.SeatDetailsResponse(
                scheduleId = scheduleId,
                eventId = eventId,
                seats = listOf(
                    EventServiceClient.SeatDetailsResponse.SeatDetail(
                        seatId = seatId,
                        seatNumber = "A-1",
                        grade = "VIP",
                        price = BigDecimal("150000")
                    )
                )
            )
    }

    private fun seedQueueToken(token: String) {
        val payload = """{"userId":"$userId","scheduleId":"$scheduleId","issuedAt":"1234567890"}"""
        stringRedisTemplate.opsForValue().set("queue:token:$token", payload)
    }

    private fun holdRequestBody(seatIds: List<UUID> = listOf(seatId)) =
        objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds))

    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("성공 시나리오")
    inner class Success {

        @Test
        @DisplayName("유효한 QueueToken으로 선점 시 201 Created와 PENDING 상태를 반환한다")
        fun returns201OnValidToken() {
            seedQueueToken(validToken)

            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequestBody())
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(150000))
                .andExpect(jsonPath("$.reservationId").isNotEmpty)
                .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty)
        }

        @Test
        @DisplayName("선점 성공 후 Redis hold_seats SET에 좌석 ID가 추가된다")
        fun addsToHoldSeatsSetOnSuccess() {
            seedQueueToken(validToken)

            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequestBody())
            ).andExpect(status().isCreated)

            val members = stringRedisTemplate.opsForSet().members("hold_seats:$scheduleId")
            assertTrue(members?.contains(seatId.toString()) == true,
                "hold_seats SET에 $seatId 가 존재해야 합니다. 실제 members: $members")
        }

        @Test
        @DisplayName("성공 시 Redisson 락을 즉시 해제한다")
        fun unlocksOnSuccess() {
            seedQueueToken(validToken)

            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequestBody())
            ).andExpect(status().isCreated)

            verify(exactly = 1) { rLock.unlock() }   // userLock
            verify(exactly = 1) { multiLock.unlock() } // multiSeatLock
        }
    }

    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("QueueToken 검증 실패")
    inner class TokenValidationFailure {

        @Test
        @DisplayName("Redis에 토큰이 없으면 401 QUEUE_TOKEN_EXPIRED를 반환한다")
        fun returns401WhenTokenMissing() {
            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", "non-existent-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequestBody())
            )
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_EXPIRED"))
        }

        @Test
        @DisplayName("토큰의 userId가 다르면 401 QUEUE_TOKEN_INVALID를 반환한다")
        fun returns401WhenUserIdMismatch() {
            val otherUserId = UUID.randomUUID()
            stringRedisTemplate.opsForValue()
                .set("queue:token:$validToken", """{"userId":"$otherUserId","scheduleId":"$scheduleId","issuedAt":"123"}""")

            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequestBody())
            )
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_INVALID"))
        }
    }

    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("요청 유효성 검증")
    inner class RequestValidation {

        @Test
        @DisplayName("seatIds가 비어있으면 400을 반환한다")
        fun returns400WhenSeatIdsEmpty() {
            seedQueueToken(validToken)

            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequestBody(emptyList()))
            ).andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("seatIds가 5개면 400 MAX_SEATS_EXCEEDED를 반환한다")
        fun returns400WhenFiveSeatsRequested() {
            seedQueueToken(validToken)
            val fiveSeats = (1..5).map { UUID.randomUUID() }
            // 5개 seats에 대한 details mock
            every { eventServiceClient.getSeatDetails(scheduleId, any()) } returns
                EventServiceClient.SeatDetailsResponse(
                    scheduleId = scheduleId,
                    eventId = eventId,
                    seats = fiveSeats.map { id ->
                        EventServiceClient.SeatDetailsResponse.SeatDetail(id, "X-$id", "VIP", BigDecimal("150000"))
                    }
                )

            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(holdRequestBody(fiveSeats))
            ).andExpect(status().isBadRequest)
        }
    }
}
