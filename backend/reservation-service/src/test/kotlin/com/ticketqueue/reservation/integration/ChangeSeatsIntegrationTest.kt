package com.ticketqueue.reservation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
@DisplayName("좌석 변경 API 통합 테스트 (PUT /reservations/hold/{reservationId})")
class ChangeSeatsIntegrationTest {

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
    @Autowired private lateinit var reservationRepository: ReservationRepository
    @Autowired private lateinit var reservationSeatRepository: ReservationSeatRepository

    @SpykBean private lateinit var redissonClient: RedissonClient
    @MockkBean private lateinit var eventServiceClient: EventServiceClient

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val oldSeatId = UUID.randomUUID()
    private val newSeatId = UUID.randomUUID()
    private val validToken = "test-queue-token-${UUID.randomUUID()}"

    private lateinit var rLock: RLock
    private lateinit var multiLock: RLock

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
        reservationRepository.deleteAll()

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
            EventServiceClient.SoldSeatsResponse(scheduleId, emptyList(), 0)
        every { eventServiceClient.getSeatDetails(scheduleId, any()) } answers {
            val requestedIds = arg<List<UUID>>(1)
            EventServiceClient.SeatDetailsResponse(
                scheduleId = scheduleId,
                eventId = eventId,
                seats = requestedIds.map { id ->
                    EventServiceClient.SeatDetailsResponse.SeatDetail(
                        seatId = id,
                        seatNumber = "A-${id.toString().take(4)}",
                        grade = "VIP",
                        price = BigDecimal("150000")
                    )
                }
            )
        }
    }

    private fun seedQueueToken(token: String = validToken) {
        stringRedisTemplate.opsForValue()
            .set("queue:token:$token", """{"userId":"$userId","scheduleId":"$scheduleId","issuedAt":"1234567890"}""")
    }

    private fun holdSeat(seatId: UUID = oldSeatId): String {
        val body = objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to listOf(seatId)))
        val result = mockMvc.perform(
            post("/reservations/hold")
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .header("X-Queue-Token", validToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isCreated).andReturn()
        return objectMapper.readTree(result.response.contentAsString)["reservationId"].asText()
    }

    private fun changeSeatsBody(seatIds: List<UUID>) =
        objectMapper.writeValueAsString(mapOf("newSeatIds" to seatIds))

    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("성공 시나리오")
    inner class Success {

        @Test
        @DisplayName("좌석 변경 시 200과 PENDING 상태, 신규 총액을 반환한다")
        fun returns200WithUpdatedAmount() {
            seedQueueToken()
            val reservationId = holdSeat()

            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(listOf(newSeatId)))
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.reservationId").value(reservationId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.newTotalAmount").value(150000))
                .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty)
        }

        @Test
        @DisplayName("변경 후 Redis hold_seats SET에 기존 좌석이 제거되고 신규 좌석이 추가된다")
        fun updatesHoldSeatsSetCorrectly() {
            seedQueueToken()
            val reservationId = holdSeat()

            // 선점 후 Redis 상태: oldSeatId 존재
            stringRedisTemplate.opsForSet().members("hold_seats:$scheduleId")!!
                .shouldContain(oldSeatId.toString())

            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(listOf(newSeatId)))
            ).andExpect(status().isOk)

            val members = stringRedisTemplate.opsForSet().members("hold_seats:$scheduleId")!!
            members.shouldContain(newSeatId.toString())
            members.shouldNotContain(oldSeatId.toString())
        }

        @Test
        @DisplayName("변경 후 DB reservation_seats가 신규 좌석으로 교체된다")
        fun updatesReservationSeatsInDb() {
            seedQueueToken()
            val reservationId = holdSeat()
            val resId = UUID.fromString(reservationId)

            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(listOf(newSeatId)))
            ).andExpect(status().isOk)

            val seats = reservationSeatRepository.findByReservationId(resId)
            seats.size shouldBe 1
            seats[0].seatId shouldBe newSeatId
        }

        @Test
        @DisplayName("일부 좌석 유지 + 일부 변경 시 유지 좌석은 기존 스냅샷을 보존한다")
        fun partialChangeKeepsOldSeatSnapshot() {
            seedQueueToken()
            val keepSeatId = UUID.randomUUID()
            val anotherNewSeatId = UUID.randomUUID()

            // 2개 좌석 선점 (keepSeatId, oldSeatId)
            val body2 = objectMapper.writeValueAsString(
                mapOf("scheduleId" to scheduleId, "seatIds" to listOf(keepSeatId, oldSeatId))
            )
            val holdResult = mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body2)
            ).andExpect(status().isCreated).andReturn()
            val reservationId = objectMapper.readTree(holdResult.response.contentAsString)["reservationId"].asText()

            // keepSeatId 유지 + anotherNewSeatId 신규
            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(listOf(keepSeatId, anotherNewSeatId)))
            ).andExpect(status().isOk)

            val seats = reservationSeatRepository.findByReservationId(UUID.fromString(reservationId))
            seats.map { it.seatId }.shouldContain(keepSeatId)
            seats.map { it.seatId }.shouldContain(anotherNewSeatId)
            seats.map { it.seatId }.shouldNotContain(oldSeatId)
            seats.size shouldBe 2
        }
    }

    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("검증 실패")
    inner class ValidationFailure {

        @Test
        @DisplayName("존재하지 않는 예매 ID면 404 RESERVATION_NOT_FOUND를 반환한다")
        fun returns404WhenReservationNotFound() {
            seedQueueToken()

            mockMvc.perform(
                put("/reservations/hold/${UUID.randomUUID()}")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(listOf(newSeatId)))
            )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"))
        }

        @Test
        @DisplayName("QueueToken이 없으면 401 QUEUE_TOKEN_EXPIRED를 반환한다")
        fun returns401WhenTokenMissing() {
            seedQueueToken()
            val reservationId = holdSeat()

            // 토큰 제거
            stringRedisTemplate.delete("queue:token:$validToken")

            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(listOf(newSeatId)))
            )
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("QUEUE_TOKEN_EXPIRED"))
        }

        @Test
        @DisplayName("이미 HOLD된 신규 좌석으로 변경하면 409 SEAT_ALREADY_HELD를 반환한다")
        fun returns409WhenNewSeatAlreadyHeld() {
            seedQueueToken()
            val reservationId = holdSeat(oldSeatId)

            // newSeatId를 다른 사용자가 이미 선점 — hold_seats SET에 직접 삽입
            stringRedisTemplate.opsForSet().add("hold_seats:$scheduleId", newSeatId.toString())

            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(listOf(newSeatId)))
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("SEAT_ALREADY_HELD"))
        }

        @Test
        @DisplayName("newSeatIds가 비어있으면 400을 반환한다")
        fun returns400WhenNewSeatIdsEmpty() {
            seedQueueToken()
            val reservationId = holdSeat()

            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody(emptyList()))
            ).andExpect(status().isBadRequest)
        }

        @Test
        @DisplayName("newSeatIds가 5개면 400 MAX_SEATS_EXCEEDED를 반환한다")
        fun returns400WhenFiveSeatsRequested() {
            seedQueueToken()
            val reservationId = holdSeat()

            mockMvc.perform(
                put("/reservations/hold/$reservationId")
                    .header("X-User-Id", userId)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", validToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(changeSeatsBody((1..5).map { UUID.randomUUID() }))
            ).andExpect(status().isBadRequest)
        }
    }
}
