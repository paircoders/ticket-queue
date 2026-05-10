package com.ticketqueue.reservation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.repository.ReservationRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 좌석 변경 동시성 테스트
 *
 * HoldSeatsConcurrencyTest 와 동일하게 RedissonClient를 mock하지 않고
 * 실제 Valkey 컨테이너에 연결하여 분산 락 경합을 검증한다.
 *
 * 보호 레이어:
 *   1차 — user:hold:lock 의 tryLock(waitTime=0): 동일 예매 동시 변경 요청 직렬화
 *   2차 — hold_seats SET: 신규 좌석 중복 차단
 */
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
@DisplayName("좌석 변경 동시성 테스트")
class ChangeSeatsConcurrencyTest {

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

    @MockkBean private lateinit var eventServiceClient: EventServiceClient

    private val scheduleId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
        reservationRepository.deleteAll()
    }

    private fun seedQueueToken(userId: UUID, token: String) {
        stringRedisTemplate.opsForValue().set(
            "queue:token:$token",
            """{"userId":"$userId","scheduleId":"$scheduleId","issuedAt":"1234567890"}"""
        )
    }

    private fun mockAllSeatsAvailable() {
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

    private fun holdSeat(userId: UUID, token: String, seatId: UUID): String {
        val body = objectMapper.writeValueAsString(
            mapOf("scheduleId" to scheduleId, "seatIds" to listOf(seatId))
        )
        val result = mockMvc.perform(
            post("/reservations/hold")
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .header("X-Queue-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn()
        check(result.response.status == 201) { "holdSeat failed: ${result.response.contentAsString}" }
        return objectMapper.readTree(result.response.contentAsString)["reservationId"].asText()
    }

    private fun changeSeats(userId: UUID, token: String, reservationId: String, newSeatIds: List<UUID>): Int {
        val body = objectMapper.writeValueAsString(mapOf("newSeatIds" to newSeatIds))
        return mockMvc.perform(
            put("/reservations/hold/$reservationId")
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .header("X-Queue-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andReturn().response.status
    }

    /**
     * 동일 예매에 2개 스레드가 동시에 다른 좌석으로 변경 요청
     *
     * user:hold:lock waitTime=0 정책으로 동일 사용자의 동시 변경 요청이 직렬화된다:
     * - 락을 먼저 획득한 1건만 성공(200)
     * - 나머지 1건은 즉시 409 RESERVATION_IN_PROGRESS
     */
    @Test
    @DisplayName("동일 예매에 2개 스레드가 동시 변경하면 1개만 성공하고 나머지는 409를 반환한다")
    fun onlyOneChangeSucceedsForSameReservation() {
        val userId = UUID.randomUUID()
        val token = "token-${UUID.randomUUID()}"
        val initialSeatId = UUID.randomUUID()
        val targetSeatA = UUID.randomUUID()
        val targetSeatB = UUID.randomUUID()
        mockAllSeatsAvailable()
        seedQueueToken(userId, token)

        val reservationId = holdSeat(userId, token, initialSeatId)

        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)

        val futureA = CompletableFuture.supplyAsync({
            startLatch.await()
            changeSeats(userId, token, reservationId, listOf(targetSeatA))
        }, executor)

        val futureB = CompletableFuture.supplyAsync({
            startLatch.await()
            changeSeats(userId, token, reservationId, listOf(targetSeatB))
        }, executor)

        startLatch.countDown()
        val statusA = futureA.get(10, TimeUnit.SECONDS)
        val statusB = futureB.get(10, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        val statuses = listOf(statusA, statusB)
        statuses.count { it == 200 } shouldBe 1
        statuses.count { it == 409 } shouldBe 1

        // DB — 예매 레코드는 여전히 1건
        reservationRepository.findAll().size shouldBe 1
    }

    /**
     * 좌석 변경과 좌석 선점이 동일 신규 좌석을 동시에 타겟
     *
     * hold_seats SET이 2차 방어선 역할:
     * - 선점이 먼저 완료되면: 변경 시 checkHoldSeatsSet → 409
     * - 변경이 먼저 완료되면: 선점 시 checkHoldSeatsSet → 409
     */
    @Test
    @DisplayName("좌석 변경과 다른 사용자 선점이 동일 좌석을 동시에 타겟하면 1개만 성공한다")
    fun changeAndHoldConflictOnSameSeat() {
        val userId1 = UUID.randomUUID()
        val token1 = "token-${UUID.randomUUID()}"
        val userId2 = UUID.randomUUID()
        val token2 = "token-${UUID.randomUUID()}"
        val initialSeatId = UUID.randomUUID()
        val contestedSeatId = UUID.randomUUID()
        mockAllSeatsAvailable()

        // userId1이 initialSeatId 선점
        seedQueueToken(userId1, token1)
        seedQueueToken(userId2, token2)
        val reservationId = holdSeat(userId1, token1, initialSeatId)

        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)

        // userId1: initialSeatId → contestedSeatId 변경
        val changeFuture = CompletableFuture.supplyAsync({
            startLatch.await()
            changeSeats(userId1, token1, reservationId, listOf(contestedSeatId))
        }, executor)

        // userId2: contestedSeatId 직접 선점
        val holdFuture = CompletableFuture.supplyAsync({
            startLatch.await()
            val body = objectMapper.writeValueAsString(
                mapOf("scheduleId" to scheduleId, "seatIds" to listOf(contestedSeatId))
            )
            mockMvc.perform(
                post("/reservations/hold")
                    .header("X-User-Id", userId2)
                    .header("X-User-Role", "USER")
                    .header("X-Queue-Token", token2)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andReturn().response.status
        }, executor)

        startLatch.countDown()
        val changeStatus = changeFuture.get(10, TimeUnit.SECONDS)
        val holdStatus = holdFuture.get(10, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // 둘 중 정확히 하나만 성공
        val successCount = listOf(changeStatus, holdStatus).count { it == 200 || it == 201 }
        successCount shouldBe 1

        // contestedSeatId가 hold_seats에 정확히 1번만 존재
        stringRedisTemplate.opsForSet().size("hold_seats:$scheduleId") shouldBe
            if (changeStatus == 200) 1L  // initialSeatId 제거됨
            else 2L  // initialSeatId + contestedSeatId

        stringRedisTemplate.opsForSet().isMember("hold_seats:$scheduleId", contestedSeatId.toString()) shouldBe true
        stringRedisTemplate.opsForSet().isMember("hold_seats:$scheduleId", initialSeatId.toString()) shouldBe (changeStatus != 200)
    }
}
