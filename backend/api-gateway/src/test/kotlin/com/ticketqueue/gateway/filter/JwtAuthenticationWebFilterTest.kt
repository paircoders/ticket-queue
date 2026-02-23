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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.Base64
import java.util.Date
import java.util.UUID

private val VALID_QUEUE_TOKEN = "qr_${UUID.randomUUID()}"

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

    companion object {
        /**
         * 일반 사용자(USER) 거부 검증용 관리자 엔드포인트 샘플.
         * 각 admin 라우트 그룹에서 1개씩 선정.
         */
        @JvmStatic
        fun adminEndpointsForUserRejection() = listOf(
            Arguments.of("POST", "/events"),
            Arguments.of("POST", "/venues"),
            Arguments.of("PUT", "/venues/1/halls/2"),
            Arguments.of("GET", "/queue/admin/stats"),
        )

        /**
         * 관리자(ADMIN) 통과 검증용 관리자 엔드포인트 샘플.
         */
        @JvmStatic
        fun adminEndpointsForAdminPass() = listOf(
            Arguments.of("POST", "/events"),
            Arguments.of("DELETE", "/venues/1"),
            Arguments.of("POST", "/venues/1/halls"),
            Arguments.of("GET", "/queue/admin/stats"),
        )
    }

    // --- 공개 엔드포인트 ---

    @Test
    fun `공개 엔드포인트는 토큰 없이 요청 시 401이 아닌 다른 응답 반환 (downstream 없어 5xx)`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectStatus().is5xxServerError // Downstream 없으므로 502/503, 401 아님
    }

    // --- 인증 실패 ---

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
    fun `role 클레임 없는 JWT로 요청하면 401 INVALID_TOKEN 반환`() {
        // JwtTokenProvider.validateAndExtract: role 없으면 JwtException("Missing role claim") 발생
        val token = createTokenWithoutRole(userId = "user-1")

        webTestClient.get()
            .uri("/reservations/seats/1")
            .header("Authorization", "Bearer $token")
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

        val token = createValidToken(jwtSecret, userId = "user-1", role = "USER")

        webTestClient.post()
            .uri("/reservations/hold")
            .header("Authorization", "Bearer $token")
            .header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, VALID_QUEUE_TOKEN) // Queue Token 필수 경로
            .exchange()
            .expectStatus().is5xxServerError // JWT + Queue Token 필터 모두 통과, downstream 없어서 5xx
    }

    // --- 관리자 인가 ---

    @ParameterizedTest(name = "USER {0} {1} -> 403 FORBIDDEN")
    @MethodSource("adminEndpointsForUserRejection")
    fun `일반 사용자가 관리자 엔드포인트 접근 시 403 FORBIDDEN 반환`(method: String, path: String) {
        every { tokenBlacklistService.isBlacklisted(any()) } returns Mono.just(false)
        val token = createValidToken(userId = "user-1", role = "USER")

        buildRequestSpec(method, path)
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isForbidden
    }

    @ParameterizedTest(name = "ADMIN {0} {1} -> downstream 전달")
    @MethodSource("adminEndpointsForAdminPass")
    fun `관리자가 관리자 엔드포인트 접근 시 downstream으로 전달`(method: String, path: String) {
        every { tokenBlacklistService.isBlacklisted(any()) } returns Mono.just(false)
        val token = createValidToken(userId = "admin-1", role = "ADMIN")

        buildRequestSpec(method, path)
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().is5xxServerError // downstream 없어 5xx, JWT 필터는 통과
    }

    @Test
    fun `관리자 엔드포인트 403 응답에 JSON 에러 형식 반환`() {
        every { tokenBlacklistService.isBlacklisted(any()) } returns Mono.just(false)
        val token = createValidToken(userId = "user-1", role = "USER")

        val responseBody = webTestClient.post()
            .uri("/events")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isForbidden
            .expectHeader().contentType("application/json")
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        responseBody shouldContain "\"code\":\"FORBIDDEN\""
        responseBody shouldContain "\"message\""
        responseBody shouldContain "\"timestamp\""
        responseBody shouldContain "\"traceId\""
    }

    // --- 헬퍼 ---

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

    private fun createTokenWithoutRole(
        userId: String,
        expirationMs: Long = 3_600_000L,
    ): String {
        val decoded = Base64.getDecoder().decode(jwtSecret)
        val key = Keys.hmacShaKeyFor(decoded)

        return Jwts.builder()
            .subject(userId)
            // "role" 클레임 없음 — JwtTokenProvider.validateAndExtract 에서 JwtException 발생
            .id(UUID.randomUUID().toString())
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()
    }

    private fun buildRequestSpec(method: String, path: String): WebTestClient.RequestHeadersSpec<*> {
        return when (method) {
            "GET" -> webTestClient.get().uri(path)
            "POST" -> webTestClient.post().uri(path)
            "PUT" -> webTestClient.put().uri(path)
            "DELETE" -> webTestClient.delete().uri(path)
            else -> error("Unsupported method: $method")
        }
    }
}
