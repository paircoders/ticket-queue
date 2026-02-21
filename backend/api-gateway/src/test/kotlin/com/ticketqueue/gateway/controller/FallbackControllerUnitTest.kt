package com.ticketqueue.gateway.controller

import com.ticketqueue.gateway.filter.TraceIdWebFilter
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/**
 * FallbackController 단위 테스트
 *
 * Spring 컨텍스트 없이 컨트롤러 메서드를 직접 호출하여 검증합니다.
 * MockServerWebExchange로 요청 헤더를 제어합니다.
 */
class FallbackControllerUnitTest {

    private val controller = FallbackController()

    private fun makeExchange(serviceName: String, traceId: String? = null): MockServerWebExchange {
        val builder = MockServerHttpRequest.get("/fallback/$serviceName")
        traceId?.let { builder.header(TraceIdWebFilter.TRACE_ID_HEADER, it) }
        return MockServerWebExchange.from(builder.build())
    }

    // ─── 503 상태 코드 ────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["user", "event", "queue", "reservation", "payment"])
    fun `모든 서비스 fallback 호출 시 503 반환`(serviceName: String) {
        val response = controller.fallback(serviceName, makeExchange(serviceName))

        response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }

    @Test
    fun `미지원 서비스명 요청 시에도 503 반환`() {
        val response = controller.fallback("unknown-service", makeExchange("unknown-service"))

        response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
    }

    // ─── 응답 body 구조 ───────────────────────────────────────────────

    @Test
    fun `응답 body에 code, message, timestamp, traceId 4개 필드가 존재해야 한다`() {
        val response = controller.fallback("user", makeExchange("user"))
        val body = response.body!!

        body.shouldContainKeys("code", "message", "timestamp", "traceId")
    }

    @Test
    fun `에러 코드는 SERVICE_UNAVAILABLE이어야 한다`() {
        val response = controller.fallback("user", makeExchange("user"))
        val body = response.body!!

        body["code"] shouldBe "SERVICE_UNAVAILABLE"
    }

    // ─── traceId 매핑 ─────────────────────────────────────────────────

    @Test
    fun `X-Trace-Id 헤더 값이 응답 body의 traceId로 매핑되어야 한다`() {
        val response = controller.fallback("user", makeExchange("user", "my-trace-123"))
        val body = response.body!!

        body["traceId"] shouldBe "my-trace-123"
    }

    @Test
    fun `X-Trace-Id 헤더 없을 때 traceId는 unknown이어야 한다`() {
        val response = controller.fallback("user", makeExchange("user"))
        val body = response.body!!

        body["traceId"] shouldBe "unknown"
    }

    // ─── 서비스별 메시지 ──────────────────────────────────────────────

    @Test
    fun `payment fallback 메시지에 이중 결제 경고가 포함되어야 한다`() {
        val response = controller.fallback("payment", makeExchange("payment"))
        val body = response.body!!

        body["message"].toString() shouldContain "이중 결제"
    }

    @Test
    fun `미지원 서비스명 요청 시 기본 메시지가 반환되어야 한다`() {
        val response = controller.fallback("unknown-service", makeExchange("unknown-service"))
        val body = response.body!!

        body["message"].toString().shouldNotBeBlank()
    }
}
