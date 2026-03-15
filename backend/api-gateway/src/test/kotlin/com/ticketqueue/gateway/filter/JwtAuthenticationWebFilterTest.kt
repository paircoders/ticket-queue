package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.gateway.BaseIntegrationTest
import com.ticketqueue.gateway.security.ReactiveTokenBlacklistService
import com.ticketqueue.gateway.support.createValidToken
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.util.UUID

private val MOCK_QUEUE_TOKEN = "qr_${UUID.randomUUID()}"

/**
 * JwtAuthenticationWebFilter 통합 테스트
 *
 * - ReactiveTokenBlacklistService: @MockkBean (Redis 불필요)
 * - 실제 JWT 토큰 생성하여 검증
 * - BaseIntegrationTest 상속 (SpringBootTest + test 프로파일)
 */
class JwtAuthenticationWebFilterTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var tokenBlacklistService: ReactiveTokenBlacklistService

    @Value("\${jwt.secret}")
    private lateinit var jwtSecret: String

    // ─── 공개 엔드포인트 ───────────────────────────────────────────────

    @Test
    fun `공개 엔드포인트는 토큰 없이 요청 시 401이 아닌 다른 응답 반환 (downstream 없어 5xx)`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectStatus().is5xxServerError // Downstream 없으므로 502/503, 401 아님
    }

    // ─── 인증 실패 ─────────────────────────────────────────────────────

    @Test
    fun `보호 엔드포인트에 토큰 없이 요청하면 401과 JSON 에러 응답 반환`() {
        val responseBody = webTestClient.get()
            .uri("/reservations/seats/1")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().contentType("application/json")
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        responseBody shouldContain "\"code\":\"UNAUTHORIZED\""
        responseBody shouldContain "\"message\""
        responseBody shouldContain "\"timestamp\""
        responseBody shouldContain "\"traceId\""
    }

    @Test
    fun `보호 엔드포인트에 잘못된 토큰으로 요청하면 401 반환`() {
        webTestClient.post()
            .uri("/reservations/hold")
            .header("Authorization", "Bearer this.is.invalid.token")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java)
            .value { body ->
                body shouldContain "\"code\":\"INVALID_TOKEN\""
            }
    }

    // ─── 헤더 인젝션 방어 ──────────────────────────────────────────────

    @Test
    fun `공개 엔드포인트에서 X-User-Id, X-User-Role 헤더 인젝션 시 downstream에 전달되지 않는다`() {
        // 공개 경로는 JWT 검증 없이 통과하지만, 인젝션 헤더는 제거되어야 함
        // downstream이 없으므로 5xx가 반환되나 401(UNAUTHORIZED)이 아닌 것이 핵심
        webTestClient.get()
            .uri("/events")
            .header(JwtAuthenticationWebFilter.USER_ID_HEADER, "99")
            .header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")
            .exchange()
            .expectStatus().is5xxServerError // downstream 부재로 5xx. 헤더 제거 직접 검증은 JwtAuthenticationWebFilterUnitTest의 capturingChain 기반 테스트로 수행.
    }

    @Test
    fun `JWT 없이 보호 경로에 X-User-Role ADMIN 인젝션 시 401 반환`() {
        webTestClient.get()
            .uri("/users/me")
            .header(JwtAuthenticationWebFilter.USER_ID_HEADER, "1")
            .header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java)
            .value { body ->
                body shouldContain "\"code\":\"UNAUTHORIZED\""
            }
    }

    @Test
    fun `유효한 토큰으로 보호 엔드포인트 접근 시 downstream으로 전달 (5xx는 downstream 부재 때문)`() {
        every { tokenBlacklistService.isBlacklisted(any()) } returns Mono.just(false)

        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")

        webTestClient.post()
            .uri("/reservations/hold")
            .header("Authorization", "Bearer $token")
            .header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, MOCK_QUEUE_TOKEN) // Queue Token 필수 경로
            .exchange()
            .expectStatus().is5xxServerError // JWT + Queue Token 필터 모두 통과, downstream 없어서 5xx
    }

}
