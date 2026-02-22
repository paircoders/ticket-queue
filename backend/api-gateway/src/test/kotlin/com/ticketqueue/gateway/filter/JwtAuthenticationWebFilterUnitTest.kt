package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.gateway.security.JwtClaims
import com.ticketqueue.gateway.security.JwtTokenProvider
import com.ticketqueue.gateway.security.ReactiveTokenBlacklistService
import com.ticketqueue.gateway.security.RouteValidator
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/**
 * JwtAuthenticationWebFilter 단위 테스트
 *
 * MockK로 JwtTokenProvider, ReactiveTokenBlacklistService를 mock.
 * RouteValidator는 실제 인스턴스 사용.
 */
class JwtAuthenticationWebFilterUnitTest {

    private val jwtTokenProvider: JwtTokenProvider = mockk()
    private val tokenBlacklistService: ReactiveTokenBlacklistService = mockk()
    private val routeValidator = RouteValidator()
    private val objectMapper = ObjectMapper()

    private val filter = JwtAuthenticationWebFilter(
        jwtTokenProvider,
        tokenBlacklistService,
        routeValidator,
        objectMapper,
    )

    private val passChain = WebFilterChain { Mono.empty() }

    // 다운스트림으로 전달된 요청 헤더를 캡처하는 체인
    private var capturedHeaders: org.springframework.http.HttpHeaders? = null
    private val capturingChain = WebFilterChain { exchange ->
        capturedHeaders = exchange.request.headers
        Mono.empty()
    }

    companion object {
        /**
         * 관리자 전용 엔드포인트 목록 — USER 거부 및 ADMIN 통과 파라미터화 테스트에서 공용으로 사용.
         * RouteValidator.adminOnlyRoutes 와 1:1 대응.
         */
        @JvmStatic
        fun adminEndpoints() = listOf(
            Arguments.of(HttpMethod.POST, "/events"),
            Arguments.of(HttpMethod.PUT, "/events/123"),
            Arguments.of(HttpMethod.DELETE, "/events/123"),
            Arguments.of(HttpMethod.POST, "/venues"),
            Arguments.of(HttpMethod.PUT, "/venues/1"),
            Arguments.of(HttpMethod.DELETE, "/venues/1"),
            Arguments.of(HttpMethod.POST, "/venues/1/halls"),
            Arguments.of(HttpMethod.PUT, "/venues/1/halls/2"),
            Arguments.of(HttpMethod.DELETE, "/venues/1/halls/2"),
            Arguments.of(HttpMethod.GET, "/queue/admin/stats"),
        )
    }

    @BeforeEach
    fun setUp() {
        capturedHeaders = null
    }

    // --- 공개 엔드포인트 통과 ---

    @Test
    fun `POST auth-login은 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.POST, "/auth/login")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null // 필터가 응답 쓰지 않음
    }

    @Test
    fun `POST auth-signup은 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.POST, "/auth/signup")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `POST auth-refresh는 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.POST, "/auth/refresh")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `GET events는 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.GET, "/events")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `GET events-id는 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.GET, "/events/123")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `GET events-schedules-id-seats는 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.GET, "/events/schedules/456/seats")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `GET actuator-health는 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.GET, "/actuator/health")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    // --- CORS preflight bypass ---

    @Test
    fun `OPTIONS preflight 요청은 관리자 엔드포인트에서도 JWT 검증 없이 통과`() {
        // POST /events는 admin 전용이지만, OPTIONS preflight는 인증 인가 없이 통과해야 함
        val exchange = exchange(HttpMethod.OPTIONS, "/events")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    // --- 인증 실패 ---

    @Test
    fun `Authorization 헤더 없으면 401 UNAUTHORIZED 반환`() {
        val exchange = exchange(HttpMethod.GET, "/reservations/seats/1")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"UNAUTHORIZED\""
        body shouldContain "\"traceId\""
        body shouldContain "\"timestamp\""
    }

    @Test
    fun `미인증 사용자가 관리자 엔드포인트 접근 시 인가 오류 403 아닌 인증 오류 401 반환`() {
        // 필터 처리 순서: 인증 step 2-4 이 인가 step 5 보다 먼저 실행
        // 따라서 토큰 없이 admin 엔드포인트 접근 시 403이 아닌 401 반환
        val exchange = exchange(HttpMethod.POST, "/events") // admin 전용, Authorization 헤더 없음

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"UNAUTHORIZED\""
    }

    @Test
    fun `Bearer 접두사 없는 헤더는 401 반환`() {
        val exchange = exchange(HttpMethod.GET, "/reservations/seats/1") {
            header(HttpHeaders.AUTHORIZATION, "Basic sometoken")
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
    }

    @Test
    fun `만료된 토큰은 401 EXPIRED_TOKEN 반환`() {
        every { jwtTokenProvider.validateAndExtract(any()) } throws
            ExpiredJwtException(null, null, "expired")

        val exchange = exchangeWithBearer(HttpMethod.GET, "/reservations/seats/1", "expired.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"EXPIRED_TOKEN\""
        body shouldContain "토큰이 만료되었습니다"
    }

    @Test
    fun `변조된 토큰은 401 INVALID_TOKEN 반환`() {
        every { jwtTokenProvider.validateAndExtract(any()) } throws
            JwtException("invalid signature")

        val exchange = exchangeWithBearer(HttpMethod.GET, "/reservations/seats/1", "tampered.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"INVALID_TOKEN\""
    }

    @Test
    fun `블랙리스트 토큰은 401 INVALID_TOKEN 반환`() {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "blacklisted-jti")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("blacklisted-jti") } returns Mono.just(true)

        val exchange = exchangeWithBearer(HttpMethod.GET, "/reservations/seats/1", "valid.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"INVALID_TOKEN\""
    }

    @Test
    fun `Redis 오류 시 fail-closed 전략으로 401 INVALID_TOKEN 반환`() {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "some-jti")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("some-jti") } returns
            Mono.error(RuntimeException("Redis connection refused"))

        val exchange = exchangeWithBearer(HttpMethod.GET, "/reservations/seats/1", "valid.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"INVALID_TOKEN\""
    }

    // --- 인가 실패 ---

    @ParameterizedTest(name = "USER {0} {1} -> 403 FORBIDDEN")
    @MethodSource("adminEndpoints")
    fun `일반 사용자가 관리자 엔드포인트 접근 시 403 반환`(method: HttpMethod, path: String) {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)

        val exchange = exchangeWithBearer(method, path, "valid.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"FORBIDDEN\""
    }

    @Test
    fun `소문자 role admin은 대소문자 구분으로 인해 403 반환`() {
        // ROLE_ADMIN = "ADMIN": 대소문자 구분 비교이므로 "admin" 은 관리자 권한으로 인정 안 됨
        val claims = JwtClaims(userId = "user-1", role = "admin", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.POST, "/events", "valid.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
    }

    @Test
    fun `일반 사용자가 path variable 없는 PUT events 요청 시 admin 경로 아님으로 처리`() {
        // isAdminOnly(PUT, /events) = false: /events/* 패턴의 * 는 세그먼트 필수
        // admin 검사를 통과하여 downstream으로 전달됨 (403 아님)
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.PUT, "/events", "valid.token")

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null // 403 아님, downstream 전달됨
    }

    // --- 403 응답 형식 ---

    @Test
    fun `관리자 엔드포인트 403 응답에 code, message, timestamp, traceId 포함`() {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.POST, "/events", "valid.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        val body = responseBody(exchange)
        body shouldContain "\"code\""
        body shouldContain "\"message\""
        body shouldContain "\"timestamp\""
        body shouldContain "\"traceId\""
    }

    // --- 성공 케이스 ---

    @ParameterizedTest(name = "ADMIN {0} {1} -> 통과")
    @MethodSource("adminEndpoints")
    fun `관리자가 관리자 엔드포인트 접근 시 통과`(method: HttpMethod, path: String) {
        val claims = JwtClaims(userId = "admin-1", role = "ADMIN", jti = "jti-admin")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-admin") } returns Mono.just(false)

        val exchange = exchangeWithBearer(method, path, "valid.token")

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null // 필터가 응답 쓰지 않음
    }

    @Test
    fun `관리자가 일반 인증 엔드포인트 접근 시 통과하고 X-User-Role ADMIN 헤더 전달`() {
        // ADMIN 역할도 admin 전용 아닌 일반 인증 엔드포인트에 정상 접근 가능해야 함
        val claims = JwtClaims(userId = "admin-1", role = "ADMIN", jti = "jti-admin")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-admin") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.POST, "/reservations/hold", "valid.token")

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
        capturedHeaders?.getFirst(JwtAuthenticationWebFilter.USER_ROLE_HEADER) shouldBe "ADMIN"
    }

    @Test
    fun `유효한 토큰 요청 시 X-User-Id, X-User-Role 헤더 downstream에 추가`() {
        val claims = JwtClaims(userId = "user-42", role = "USER", jti = "jti-42")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-42") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.POST, "/reservations/hold", "valid.token")

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        capturedHeaders?.getFirst(JwtAuthenticationWebFilter.USER_ID_HEADER) shouldBe "user-42"
        capturedHeaders?.getFirst(JwtAuthenticationWebFilter.USER_ROLE_HEADER) shouldBe "USER"
    }

    @Test
    fun `에러 응답 JSON에 code, message, timestamp, traceId 모두 포함`() {
        val exchange = exchange(HttpMethod.GET, "/reservations/seats/1")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        val body = responseBody(exchange)
        body shouldContain "\"code\""
        body shouldContain "\"message\""
        body shouldContain "\"timestamp\""
        body shouldContain "\"traceId\""
    }

    // --- 헬퍼 ---

    private fun exchange(
        method: HttpMethod,
        path: String,
        block: MockServerHttpRequest.BaseBuilder<*>.() -> Unit = {},
    ): MockServerWebExchange {
        val builder = when (method) {
            HttpMethod.GET -> MockServerHttpRequest.get(path)
            HttpMethod.POST -> MockServerHttpRequest.post(path)
            HttpMethod.PUT -> MockServerHttpRequest.put(path)
            HttpMethod.DELETE -> MockServerHttpRequest.delete(path)
            HttpMethod.OPTIONS -> MockServerHttpRequest.options(path)
            else -> error("Unsupported HTTP method: $method")
        }
        block(builder)
        return MockServerWebExchange.from(builder.build())
    }

    private fun exchangeWithBearer(
        method: HttpMethod,
        path: String,
        token: String,
    ): MockServerWebExchange = exchange(method, path) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun responseBody(exchange: MockServerWebExchange): String {
        val body = exchange.response.bodyAsString.block() ?: ""
        return body
    }
}
