package com.ticketqueue.gateway.filter

import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.gateway.BaseIntegrationTest
import com.ticketqueue.gateway.security.ReactiveTokenBlacklistService
import com.ticketqueue.gateway.support.createValidToken
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * QueueTokenWebFilter 통합 테스트
 *
 * - ReactiveTokenBlacklistService: @MockkBean (Redis 불필요)
 * - 실제 JWT 토큰 생성하여 JWT 필터와 Queue Token 필터 연계 검증
 * - BaseIntegrationTest 상속 (SpringBootTest + test 프로파일)
 *
 * 필터 실행 순서: TraceId → SecurityHeaders → JWT(+2) → QueueToken(+3)
 * JWT 없음 → JWT 필터가 먼저 401 반환, Queue Token 필터 미실행
 */
class QueueTokenWebFilterTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockkBean
    private lateinit var tokenBlacklistService: ReactiveTokenBlacklistService

    @Value("\${jwt.secret}")
    private lateinit var jwtSecret: String

    @BeforeEach
    fun setUp() {
        every { tokenBlacklistService.isBlacklisted(any()) } returns Mono.just(false)
    }

    // ─── JWT 유효 + Queue Token 누락 ──────────────────────────────────

    @Test
    fun `JWT 유효하고 Queue Token 없으면 401 QUEUE_TOKEN_MISSING 반환`() {
        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")

        val body = webTestClient.post()
            .uri("/reservations/hold")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    // ─── JWT 유효 + Queue Token 형식 오류 ─────────────────────────────

    @Test
    fun `JWT 유효하고 Queue Token 형식 오류면 401 QUEUE_TOKEN_INVALID 반환`() {
        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")

        val body = webTestClient.post()
            .uri("/payments")
            .header("Authorization", "Bearer $token")
            .header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, "invalid-token-format")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        body shouldContain "\"code\":\"QUEUE_TOKEN_INVALID\""
    }

    // ─── JWT 유효 + Queue Token 유효 ──────────────────────────────────

    @Test
    fun `JWT 유효하고 유효한 Queue Token이면 downstream으로 전달 (5xx는 downstream 부재 때문)`() {
        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")
        val queueToken = "qr_${UUID.randomUUID()}"

        webTestClient.post()
            .uri("/reservations/hold")
            .header("Authorization", "Bearer $token")
            .header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, queueToken)
            .exchange()
            .expectStatus().is5xxServerError // 두 필터 모두 통과, downstream 없어서 5xx
    }

    // ─── JWT 없음 + Queue Token 있음 ──────────────────────────────────

    @Test
    fun `JWT 없고 Queue Token 있어도 JWT 필터가 먼저 401 반환`() {
        val queueToken = "qr_${UUID.randomUUID()}"

        val body = webTestClient.post()
            .uri("/reservations/hold")
            .header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, queueToken)
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        body shouldContain "\"code\":\"UNAUTHORIZED\""
    }

    // ─── Queue Token 불필요 경로 ──────────────────────────────────────

    @Test
    fun `Queue Token 불필요 경로는 Queue Token 없어도 downstream으로 전달 (5xx는 downstream 부재)`() {
        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")

        webTestClient.get()
            .uri("/reservations")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().is5xxServerError // Queue Token 필터 미개입, downstream 없어서 5xx
    }

    // ─── JWT 유효 + Queue Token 유효 (추가 경로) ──────────────────────

    @Test
    fun `JWT 유효하고 유효한 Queue Token으로 POST payments-confirm 요청 시 downstream 전달 (5xx는 downstream 부재)`() {
        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")
        val queueToken = "qr_${UUID.randomUUID()}"

        webTestClient.post()
            .uri("/payments/confirm")
            .header("Authorization", "Bearer $token")
            .header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, queueToken)
            .exchange()
            .expectStatus().is5xxServerError // 두 필터 모두 통과, downstream 없어서 5xx
    }

    @Test
    fun `JWT 유효하고 Queue Token 없으면 GET reservations-seats-id에서 401 QUEUE_TOKEN_MISSING 반환`() {
        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")

        val body = webTestClient.get()
            .uri("/reservations/seats/123")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    @Test
    fun `JWT 유효하고 유효한 Queue Token으로 PUT reservations-hold-id 요청 시 downstream 전달 (5xx는 downstream 부재)`() {
        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")
        val queueToken = "qr_${UUID.randomUUID()}"

        webTestClient.put()
            .uri("/reservations/hold/456")
            .header("Authorization", "Bearer $token")
            .header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, queueToken)
            .exchange()
            .expectStatus().is5xxServerError // 두 필터 모두 통과, downstream 없어서 5xx
    }
}
