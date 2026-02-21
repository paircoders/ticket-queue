package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.gateway.BaseIntegrationTest
import com.ticketqueue.gateway.security.ReactiveTokenBlacklistService
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.util.Base64
import java.util.Date
import java.util.UUID

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

    @Test
    fun `유효한 토큰으로 보호 엔드포인트 접근 시 downstream으로 전달 (5xx는 downstream 부재 때문)`() {
        every { tokenBlacklistService.isBlacklisted(any()) } returns Mono.just(false)

        val token = createValidToken(userId = "user-1", role = "USER")

        webTestClient.post()
            .uri("/reservations/hold")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().is5xxServerError // JWT 필터 통과 후 downstream 없어서 5xx
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────

    private fun createValidToken(
        userId: String,
        role: String,
        expirationMs: Long = 3_600_000L, // 1시간
    ): String {
        val decoded = Base64.getDecoder().decode(jwtSecret)
        val key = Keys.hmacShaKeyFor(decoded)

        return Jwts.builder()
            .subject(userId)
            .claim("role", role)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()
    }
}
