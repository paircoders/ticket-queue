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
 * 좌석 선점 동시성 테스트
 *
 * HoldSeatsIntegrationTest 와의 차이점:
 * - RedissonClient를 mock하지 않고 실제 Valkey 컨테이너에 연결한다.
 * - 여러 스레드가 CountDownLatch로 동시에 출발해 실제 분산 락 경합을 유발한다.
 *
 * 보호 레이어:
 *   1차 — Redisson tryLock(waitTime=0): 락을 선점한 스레드 외 나머지는 즉시 false 반환 → 409
 *   2차 — hold_seats SET: 락 해제 후 재시도해도 SET에 이미 존재하면 → 409
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
@DisplayName("좌석 선점 동시성 테스트")
class HoldSeatsConcurrencyTest {

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
        val valkey = GenericContainer("valkey/valkey:8.1-alpine")
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

    // EventServiceClient만 mock — RedissonClient는 실제 Valkey 컨테이너 사용
    @MockkBean private lateinit var eventServiceClient: EventServiceClient

    private val scheduleId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory?.connection?.serverCommands()?.flushAll()
        reservationRepository.deleteAll()
    }

    // ─────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────

    private fun seedQueueToken(userId: UUID, token: String) {
        stringRedisTemplate.opsForValue().set(
            "queue:token:$token",
            """{"userId":"$userId","scheduleId":"$scheduleId","issuedAt":"1234567890"}"""
        )
    }

    /**
     * getSeatDetails 응답을 요청된 seatId 목록 기준으로 동적으로 반환한다.
     * 동시성 테스트에서 좌석마다 다른 UUID를 쓰므로, 고정 mock 대신 answers 블록을 사용한다.
     */
    private fun mockAllSeatsAvailable() {
        every { eventServiceClient.getSoldSeats(scheduleId) } returns
            EventServiceClient.SoldSeatsResponse(scheduleId, emptyList())

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

    private fun holdRequestBody(seatIds: List<UUID>): String =
        objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId, "seatIds" to seatIds))

    // ─────────────────────────────────────────────────────────
    // 테스트
    // ─────────────────────────────────────────────────────────

    /**
     * 핵심 시나리오: 동일 좌석에 N명이 동시 요청 → 정확히 1명만 성공
     *
     * 흐름:
     *  - 10개 스레드가 CountDownLatch로 동시에 출발
     *  - 각 스레드는 tryLock(waitTime=0) 으로 Valkey 분산 락 경합
     *  - 락을 획득한 1명이 DB + hold_seats SET을 갱신하고 락 해제
     *  - 나머지 9명은 tryLock 실패(즉시) → 409 SEAT_ALREADY_HELD
     *  - 락 해제 후 재시도하더라도 hold_seats SET 확인에서 추가로 차단됨
     */
    @Test
    @DisplayName("동일 좌석에 N명이 동시 요청하면 정확히 1명만 성공하고 나머지는 409를 반환한다")
    fun onlyOneSucceedsForSameSeatConcurrentRequests() {
        val concurrency = 10
        val seatId = UUID.randomUUID()
        mockAllSeatsAvailable()

        val users = List(concurrency) { UUID.randomUUID() }
        val tokens = List(concurrency) { "token-${UUID.randomUUID()}" }
        users.zip(tokens).forEach { (userId, token) -> seedQueueToken(userId, token) }

        val requestBody = holdRequestBody(listOf(seatId))
        val executor = Executors.newFixedThreadPool(concurrency)
        val startLatch = CountDownLatch(1)

        val futures = users.zip(tokens).map { (userId, token) ->
            CompletableFuture.supplyAsync({
                startLatch.await()  // 모든 스레드 준비 완료 후 일제히 출발
                mockMvc.perform(
                    post("/reservations/hold")
                        .header("X-User-Id", userId)
                        .header("X-User-Role", "USER")
                        .header("X-Queue-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                ).andReturn().response.status
            }, executor)
        }

        startLatch.countDown()
        val statuses = futures.map { it.get() }
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()

        // HTTP 응답 검증
        statuses.count { it == 201 } shouldBe 1
        statuses.count { it == 409 } shouldBe concurrency - 1

        // DB — 예매 레코드가 정확히 1건
        reservationRepository.findAll().size shouldBe 1

        // Redis — hold_seats SET에 해당 좌석이 1건
        stringRedisTemplate.opsForSet().size("hold_seats:$scheduleId") shouldBe 1L
    }

    /**
     * 선점 성공 후 동일 좌석 재요청: hold_seats SET이 2차 방어선 역할을 한다.
     *
     * 분산 락은 이미 해제된 상태이지만, hold_seats SET에 이미 존재하므로
     * checkHoldSeatsSet 에서 차단 → 409 SEAT_ALREADY_HELD
     */
    @Test
    @DisplayName("선점 성공 후 동일 좌석 재요청은 409 SEAT_ALREADY_HELD를 반환한다")
    fun secondRequestForSameSeatFailsAfterFirstSucceeds() {
        val seatId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val token = "token-${UUID.randomUUID()}"
        mockAllSeatsAvailable()
        seedQueueToken(userId, token)

        val requestBody = holdRequestBody(listOf(seatId))

        // 1차 요청 — 성공
        val firstStatus = mockMvc.perform(
            post("/reservations/hold")
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .header("X-Queue-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        ).andReturn().response.status
        firstStatus shouldBe 201

        // 2차 요청 — hold_seats SET에서 차단 (분산 락은 이미 해제됨)
        val secondStatus = mockMvc.perform(
            post("/reservations/hold")
                .header("X-User-Id", userId)
                .header("X-User-Role", "USER")
                .header("X-Queue-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody)
        ).andReturn().response.status
        secondStatus shouldBe 409

        reservationRepository.findAll().size shouldBe 1
    }

    /**
     * 서로 다른 좌석 동시 요청: 락은 좌석별로 독립적이므로 경합 없이 모두 성공해야 한다.
     */
    @Test
    @DisplayName("서로 다른 유저가 서로 다른 좌석을 동시 요청하면 모두 성공한다")
    fun allSucceedWhenDifferentUsersRequestDifferentSeats() {
        val concurrency = 4
        val seatIds = List(concurrency) { UUID.randomUUID() }
        mockAllSeatsAvailable()

        val users = List(concurrency) { UUID.randomUUID() }
        val tokens = List(concurrency) { "token-${UUID.randomUUID()}" }
        users.zip(tokens).forEach { (userId, token) -> seedQueueToken(userId, token) }

        val executor = Executors.newFixedThreadPool(concurrency)
        val startLatch = CountDownLatch(1)

        val futures = (0 until concurrency).map { i ->
            CompletableFuture.supplyAsync({
                startLatch.await()
                mockMvc.perform(
                    post("/reservations/hold")
                        .header("X-User-Id", users[i])
                        .header("X-User-Role", "USER")
                        .header("X-Queue-Token", tokens[i])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestBody(listOf(seatIds[i])))
                ).andReturn().response.status
            }, executor)
        }

        startLatch.countDown()
        val statuses = futures.map { it.get() }
        executor.shutdown()

        statuses.count { it == 201 } shouldBe concurrency
        reservationRepository.findAll().size shouldBe concurrency
        stringRedisTemplate.opsForSet().size("hold_seats:$scheduleId") shouldBe concurrency.toLong()
    }

    /**
     * 동일 유저가 동일 회차의 서로 다른 좌석을 동시에 1장씩 요청하는 경우.
     *
     * user:hold:lock:{userId}:{scheduleId} 의 waitTime=0 정책에 의해
     * 동일 유저의 동시 요청은 직렬화된다: 락을 먼저 획득한 1건만 201로 성공하고
     * 나머지 (concurrency - 1) 건은 즉시 409 RESERVATION_IN_PROGRESS로 실패한다.
     */
    @Test
    @DisplayName("동일 유저가 서로 다른 좌석을 동시 요청하면 유저 락 직렬화로 1개만 성공한다")
    fun sameUserConcurrentDifferentSeatsOnlyOneSucceeds() {
        val concurrency = 4
        val seatIds = List(concurrency) { UUID.randomUUID() }
        val userId = UUID.randomUUID()
        mockAllSeatsAvailable()

        // 동일 유저이므로 토큰마다 같은 userId를 심는다
        val tokens = List(concurrency) { "token-${UUID.randomUUID()}" }
        tokens.forEach { token -> seedQueueToken(userId, token) }

        val executor = Executors.newFixedThreadPool(concurrency)
        val startLatch = CountDownLatch(1)

        val futures = (0 until concurrency).map { i ->
            CompletableFuture.supplyAsync({
                startLatch.await()
                mockMvc.perform(
                    post("/reservations/hold")
                        .header("X-User-Id", userId)
                        .header("X-User-Role", "USER")
                        .header("X-Queue-Token", tokens[i])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdRequestBody(listOf(seatIds[i])))
                ).andReturn().response.status
            }, executor)
        }

        startLatch.countDown()
        val statuses = futures.map { it.get() }
        executor.shutdown()

        // waitTime=0 유저 락으로 동시 요청은 직렬화 — 정확히 1건만 성공
        statuses.count { it == 201 } shouldBe 1
        statuses.count { it == 409 } shouldBe (concurrency - 1)
        reservationRepository.findAll().size shouldBe 1
    }
}
