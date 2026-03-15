package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.gateway.config.JwtProperties
import com.ticketqueue.gateway.security.JwtClaims
import com.ticketqueue.gateway.security.JwtTokenProvider
import com.ticketqueue.gateway.security.ReactiveTokenBlacklistService
import com.ticketqueue.gateway.security.RouteValidator
import com.ticketqueue.gateway.support.exchange
import com.ticketqueue.gateway.support.responseBody
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
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

    // 기본 CircuitBreakerRegistry (CLOSED 상태로 시작)
    private val circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults()

    private val filter = JwtAuthenticationWebFilter(
        jwtTokenProvider,
        tokenBlacklistService,
        routeValidator,
        objectMapper,
        circuitBreakerRegistry,
    )

    private val passChain = WebFilterChain { Mono.empty() }

    // 다운스트림으로 전달된 요청 헤더를 캡처하는 체인
    private var capturedHeaders: org.springframework.http.HttpHeaders? = null
    private val capturingChain = WebFilterChain { exchange ->
        capturedHeaders = exchange.request.headers
        Mono.empty()
    }

    @BeforeEach
    fun setUp() {
        capturedHeaders = null
    }

    // ─── 공개 엔드포인트 통과 ───────────────────────────────────────────

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

    @Test
    fun `OPTIONS preflight 요청은 JWT 검증 없이 통과`() {
        val exchange = exchange(HttpMethod.OPTIONS, "/reservations/seats/1")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    // ─── 인증 실패 ─────────────────────────────────────────────────────

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
    fun `IllegalArgumentException 발생 시 401 INVALID_TOKEN`() {
        every { jwtTokenProvider.validateAndExtract(any()) } throws
            IllegalArgumentException("JWT String argument cannot be null or empty")

        val exchange = exchangeWithBearer(HttpMethod.GET, "/reservations/seats/1", "blank.token")

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
    fun `CircuitBreaker CLOSED 상태에서 Redis 오류 시 fail-closed 전략으로 401 INVALID_TOKEN 반환`() {
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

    @Test
    fun `CircuitBreaker OPEN 상태에서 fail-open 전략으로 요청 허용`() {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "open-jti")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        // Mono 객체 생성은 허용하되 실제 구독은 CircuitBreaker가 차단
        every { tokenBlacklistService.isBlacklisted("open-jti") } returns Mono.just(false)

        // CircuitBreaker를 강제로 OPEN 상태로 전환
        val openRegistry = CircuitBreakerRegistry.ofDefaults()
        val cb = openRegistry.circuitBreaker("redisBlacklist")
        cb.transitionToOpenState()

        val openFilter = JwtAuthenticationWebFilter(
            jwtTokenProvider,
            tokenBlacklistService,
            routeValidator,
            objectMapper,
            openRegistry,
        )

        val exchange = exchangeWithBearer(HttpMethod.GET, "/reservations/seats/1", "valid.token")

        StepVerifier.create(openFilter.filter(exchange, capturingChain))
            .verifyComplete()

        // OPEN 상태: fail-open → 요청 통과 (응답 코드 없음)
        exchange.response.statusCode shouldBe null
        capturedHeaders?.getFirst(JwtAuthenticationWebFilter.USER_ID_HEADER) shouldBe "user-1"
    }

    // ─── 인가 실패 ─────────────────────────────────────────────────────

    @Test
    fun `일반 사용자가 관리자 엔드포인트 접근 시 403 FORBIDDEN 반환`() {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.POST, "/events", "valid.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"FORBIDDEN\""
        body shouldContain "접근 권한이 없습니다"
    }

    @Test
    fun `허용되지 않은 role이 JWT에 포함된 경우 403 FORBIDDEN 반환`() {
        val claims = JwtClaims(userId = "user-1", role = "SUPERADMIN", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.GET, "/reservations/seats/1", "valid.token")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"FORBIDDEN\""
        body shouldContain "접근 권한이 없습니다"
    }

    // ─── 성공 케이스 ───────────────────────────────────────────────────

    @Test
    fun `관리자가 관리자 엔드포인트 접근 시 통과`() {
        val claims = JwtClaims(userId = "admin-1", role = "ADMIN", jti = "jti-admin")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-admin") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.POST, "/events", "valid.token")

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null // 필터가 응답 쓰지 않음
    }

    @Test
    fun `유효한 토큰 요청 시 X-User-Id, X-User-Role 헤더 downstream에 추가`() {
        val claims = JwtClaims(userId = "user-42", role = "USER", jti = "jti-42")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-42") } returns Mono.just(false)

        val exchange = exchangeWithBearer(HttpMethod.POST, "/reservations/hold", "valid.token")

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        capturedHeaders shouldNotBe null
        capturedHeaders!!.getFirst(JwtAuthenticationWebFilter.USER_ID_HEADER) shouldBe "user-42"
        capturedHeaders!!.getFirst(JwtAuthenticationWebFilter.USER_ROLE_HEADER) shouldBe "USER"
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

    // ─── 헤더 인젝션 방어 ──────────────────────────────────────────────

    @Test
    fun `공개 경로에서 X-User-Id, X-User-Role 인젝션 헤더가 제거된 채로 chain에 전달된다`() {
        val exchange = exchange(HttpMethod.GET, "/events") {
            header(JwtAuthenticationWebFilter.USER_ID_HEADER, "99")
            header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")
        }

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        capturedHeaders shouldNotBe null
        capturedHeaders!!.containsKey(JwtAuthenticationWebFilter.USER_ID_HEADER) shouldBe false
        capturedHeaders!!.containsKey(JwtAuthenticationWebFilter.USER_ROLE_HEADER) shouldBe false
    }

    @Test
    fun `보호 경로에서 인젝션된 X-User-Role이 JWT 클레임 값으로 대체된다`() {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)

        val exchange = exchange(HttpMethod.POST, "/reservations/hold") {
            header(HttpHeaders.AUTHORIZATION, "Bearer valid.token")
            header(JwtAuthenticationWebFilter.USER_ID_HEADER, "malicious-id")
            header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")
        }

        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()

        capturedHeaders shouldNotBe null
        capturedHeaders!!.getFirst(JwtAuthenticationWebFilter.USER_ID_HEADER) shouldBe "user-1"
        capturedHeaders!!.getFirst(JwtAuthenticationWebFilter.USER_ROLE_HEADER) shouldBe "USER"
    }

    @Test
    fun `JWT 없이 보호 경로에 X-User-Id, X-User-Role 인젝션 시 401 반환`() {
        val exchange = exchange(HttpMethod.GET, "/reservations/seats/1") {
            header(JwtAuthenticationWebFilter.USER_ID_HEADER, "1")
            header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"UNAUTHORIZED\""
    }

    @Test
    fun `OPTIONS 요청에 X-User-Id, X-User-Role 인젝션 헤더가 포함되어도 chain에 제거된 채로 전달된다`() {
        val exchange = exchange(HttpMethod.OPTIONS, "/events") {
            header(JwtAuthenticationWebFilter.USER_ID_HEADER, "99")
            header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")
        }
        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()
        capturedHeaders shouldNotBe null
        capturedHeaders!!.containsKey(JwtAuthenticationWebFilter.USER_ID_HEADER) shouldBe false
        capturedHeaders!!.containsKey(JwtAuthenticationWebFilter.USER_ROLE_HEADER) shouldBe false
    }

    @Test
    fun `ADMIN 전용 경로에 USER 토큰으로 ADMIN role 인젝션 시 인젝션이 무시되고 403 반환`() {
        val claims = JwtClaims(userId = "user-1", role = "USER", jti = "jti-1")
        every { jwtTokenProvider.validateAndExtract(any()) } returns claims
        every { tokenBlacklistService.isBlacklisted("jti-1") } returns Mono.just(false)
        val exchange = exchange(HttpMethod.POST, "/events") {
            header(HttpHeaders.AUTHORIZATION, "Bearer valid.token")
            header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")  // 인젝션 시도
        }
        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()
        exchange.response.statusCode shouldBe HttpStatus.FORBIDDEN
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"FORBIDDEN\""
    }

    @Test
    fun `동일 헤더를 여러 값으로 인젝션해도 downstream에 전혀 전달되지 않는다`() {
        val exchange = exchange(HttpMethod.GET, "/events") {
            header(JwtAuthenticationWebFilter.USER_ID_HEADER, "id-1")
            header(JwtAuthenticationWebFilter.USER_ID_HEADER, "id-2")  // 같은 헤더 두 번
            header(JwtAuthenticationWebFilter.USER_ROLE_HEADER, "ADMIN")
        }
        StepVerifier.create(filter.filter(exchange, capturingChain))
            .verifyComplete()
        capturedHeaders shouldNotBe null
        capturedHeaders!!.getOrEmpty(JwtAuthenticationWebFilter.USER_ID_HEADER) shouldBe emptyList()
        capturedHeaders!!.getOrEmpty(JwtAuthenticationWebFilter.USER_ROLE_HEADER) shouldBe emptyList()
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────

    private fun exchangeWithBearer(
        method: HttpMethod,
        path: String,
        token: String,
    ): MockServerWebExchange = exchange(method, path) {
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }
}
