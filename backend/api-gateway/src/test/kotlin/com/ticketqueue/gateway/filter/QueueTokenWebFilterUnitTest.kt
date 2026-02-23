package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.gateway.security.RouteValidator
import com.ticketqueue.gateway.support.exchange
import com.ticketqueue.gateway.support.responseBody
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.UUID

/**
 * QueueTokenWebFilter 단위 테스트
 *
 * RouteValidator는 실제 인스턴스 사용 (JwtAuthenticationWebFilterUnitTest 패턴 준수).
 * ObjectMapper는 직접 생성.
 * MockServerWebExchange + StepVerifier 사용.
 */
class QueueTokenWebFilterUnitTest {

    private val routeValidator = RouteValidator()
    private val objectMapper = ObjectMapper()

    private val filter = QueueTokenWebFilter(routeValidator, objectMapper)

    private val passChain = WebFilterChain { Mono.empty() }

    // ─── Queue Token 불필요 경로 통과 ──────────────────────────────────

    @Test
    fun `GET events는 Queue Token 없이 통과`() {
        val exchange = exchange(HttpMethod.GET, "/events")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null // 필터가 응답 쓰지 않음
    }

    @Test
    fun `GET reservations는 Queue Token 없이 통과`() {
        val exchange = exchange(HttpMethod.GET, "/reservations")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `GET payments-id는 Queue Token 없이 통과`() {
        val exchange = exchange(HttpMethod.GET, "/payments/123")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `DELETE reservations-hold-id는 Queue Token 없이 통과`() {
        val exchange = exchange(HttpMethod.DELETE, "/reservations/hold/123")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `OPTIONS reservations-hold는 Queue Token 없이 통과`() {
        val exchange = exchange(HttpMethod.OPTIONS, "/reservations/hold")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    // ─── Queue Token 누락 → 401 QUEUE_TOKEN_MISSING ──────────────────

    @Test
    fun `POST reservations-hold에 Queue Token 없으면 401 QUEUE_TOKEN_MISSING 반환`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
        body shouldContain "대기열 토큰이 필요합니다"
    }

    @Test
    fun `GET reservations-seats-id에 Queue Token 없으면 401 QUEUE_TOKEN_MISSING 반환`() {
        val exchange = exchange(HttpMethod.GET, "/reservations/seats/123")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    @Test
    fun `PUT reservations-hold-id에 Queue Token 없으면 401 QUEUE_TOKEN_MISSING 반환`() {
        val exchange = exchange(HttpMethod.PUT, "/reservations/hold/456")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    @Test
    fun `POST payments에 Queue Token 없으면 401 QUEUE_TOKEN_MISSING 반환`() {
        val exchange = exchange(HttpMethod.POST, "/payments")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    @Test
    fun `POST payments-confirm에 Queue Token 없으면 401 QUEUE_TOKEN_MISSING 반환`() {
        val exchange = exchange(HttpMethod.POST, "/payments/confirm")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    @Test
    fun `빈 문자열 Queue Token이면 401 QUEUE_TOKEN_MISSING 반환`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, "")
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    // ─── 형식 오류 → 401 QUEUE_TOKEN_INVALID ─────────────────────────

    @Test
    fun `qr_ prefix 없는 토큰은 401 QUEUE_TOKEN_INVALID 반환`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, "abc-def")
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_INVALID\""
        body shouldContain "유효하지 않은 대기열 토큰입니다"
    }

    @Test
    fun `qr_ prefix만 있고 UUID 형식 아닌 토큰은 401 QUEUE_TOKEN_INVALID 반환`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, "qr_not-a-uuid")
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_INVALID\""
    }

    @Test
    fun `대문자 UUID 포함 토큰은 401 QUEUE_TOKEN_INVALID 반환`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, "qr_550E8400-E29B-41D4-A716-446655440000")
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_INVALID\""
    }

    // ─── 통과 케이스 ──────────────────────────────────────────────────

    @Test
    fun `유효한 qr_ + UUID 토큰으로 POST reservations-hold 요청 시 통과`() {
        val validToken = "qr_${UUID.randomUUID()}"
        val exchange = exchange(HttpMethod.POST, "/reservations/hold") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, validToken)
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null // 필터가 응답 쓰지 않음
    }

    @Test
    fun `유효한 토큰으로 POST payments 요청 시 통과`() {
        val validToken = "qr_${UUID.randomUUID()}"
        val exchange = exchange(HttpMethod.POST, "/payments") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, validToken)
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `유효한 토큰으로 GET reservations-seats-id 요청 시 통과`() {
        val validToken = "qr_${UUID.randomUUID()}"
        val exchange = exchange(HttpMethod.GET, "/reservations/seats/123") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, validToken)
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `유효한 토큰으로 PUT reservations-hold-id 요청 시 통과`() {
        val validToken = "qr_${UUID.randomUUID()}"
        val exchange = exchange(HttpMethod.PUT, "/reservations/hold/456") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, validToken)
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `유효한 토큰으로 POST payments-confirm 요청 시 통과`() {
        val validToken = "qr_${UUID.randomUUID()}"
        val exchange = exchange(HttpMethod.POST, "/payments/confirm") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, validToken)
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe null
    }

    @Test
    fun `공백 문자열 Queue Token이면 401 QUEUE_TOKEN_MISSING 반환`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold") {
            header(QueueTokenWebFilter.QUEUE_TOKEN_HEADER, "   ")
        }

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        exchange.response.statusCode shouldBe HttpStatus.UNAUTHORIZED
        val body = responseBody(exchange)
        body shouldContain "\"code\":\"QUEUE_TOKEN_MISSING\""
    }

    // ─── 에러 응답 형식 ───────────────────────────────────────────────

    @Test
    fun `에러 응답 JSON에 code, message, timestamp, traceId 모두 포함`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold")

        StepVerifier.create(filter.filter(exchange, passChain))
            .verifyComplete()

        val body = responseBody(exchange)
        body shouldContain "\"code\""
        body shouldContain "\"message\""
        body shouldContain "\"timestamp\""
        body shouldContain "\"traceId\""
    }

}
