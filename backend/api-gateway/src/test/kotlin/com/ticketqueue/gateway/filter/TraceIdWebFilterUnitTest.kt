package com.ticketqueue.gateway.filter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/**
 * TraceIdWebFilter 단위 테스트
 *
 * WebTestClient 없이 MockServerWebExchange로 필터 로직만 검증
 */
class TraceIdWebFilterUnitTest {

    private val filter = TraceIdWebFilter()

    private val mockChain = WebFilterChain { exchange ->
        // Chain은 단순히 완료 신호만 반환 (downstream 없음)
        Mono.empty()
    }

    @Test
    fun `헤더 없는 요청 시 UUID 생성하여 응답 헤더에 설정`() {
        // Given: TraceId 헤더가 없는 요청
        val request = MockServerHttpRequest.get("/test").build()
        val exchange = MockServerWebExchange.from(request)

        // When: 필터 실행
        val result = filter.filter(exchange, mockChain)

        // Then: 응답 헤더에 UUID 형식의 TraceId 존재
        StepVerifier.create(result)
            .verifyComplete()

        val responseTraceId = exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
        responseTraceId.shouldNotBeBlank()
        // UUID 형식 검증 (8-4-4-4-12 패턴)
        responseTraceId!! shouldContain "-"
    }

    @Test
    fun `기존 TraceId 헤더가 있으면 그대로 유지`() {
        // Given: TraceId 헤더가 있는 요청
        val existingTraceId = "existing-trace-id-12345"
        val request = MockServerHttpRequest.get("/test")
            .header(TraceIdWebFilter.TRACE_ID_HEADER, existingTraceId)
            .build()
        val exchange = MockServerWebExchange.from(request)

        // When: 필터 실행
        val result = filter.filter(exchange, mockChain)

        // Then: 응답 헤더에 기존 TraceId 그대로 반환
        StepVerifier.create(result)
            .verifyComplete()

        val responseTraceId = exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
        responseTraceId shouldBe existingTraceId
    }

    @Test
    fun `빈 TraceId 헤더는 무시하고 새로 생성`() {
        // Given: 빈 TraceId 헤더
        val request = MockServerHttpRequest.get("/test")
            .header(TraceIdWebFilter.TRACE_ID_HEADER, "  ")
            .build()
        val exchange = MockServerWebExchange.from(request)

        // When: 필터 실행
        val result = filter.filter(exchange, mockChain)

        // Then: 새로운 UUID 생성
        StepVerifier.create(result)
            .verifyComplete()

        val responseTraceId = exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
        responseTraceId.shouldNotBeBlank()
        responseTraceId shouldNotBe "  "
    }

    @Test
    fun `Reactor Context에 traceId 저장 검증`() {
        // Given: 헤더 없는 요청
        val request = MockServerHttpRequest.get("/test").build()
        val exchange = MockServerWebExchange.from(request)

        // Context 캡처용 체인
        var capturedTraceId: String? = null
        val capturingChain = WebFilterChain { ex ->
            Mono.deferContextual { ctx ->
                capturedTraceId = ctx.getOrDefault(TraceIdWebFilter.TRACE_ID_MDC_KEY, null)
                Mono.empty()
            }
        }

        // When: 필터 실행
        val result = filter.filter(exchange, capturingChain)

        // Then: Context에 traceId 존재
        StepVerifier.create(result)
            .verifyComplete()

        capturedTraceId.shouldNotBeBlank()
        capturedTraceId shouldBe exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
    }

    @Test
    fun `Downstream 요청에 TraceId 헤더 전파`() {
        // Given: 헤더 없는 요청
        val request = MockServerHttpRequest.get("/test").build()
        val exchange = MockServerWebExchange.from(request)

        // Mutated request 캡처용 체인
        var downstreamHeaders: HttpHeaders? = null
        val capturingChain = WebFilterChain { ex ->
            downstreamHeaders = ex.request.headers
            Mono.empty()
        }

        // When: 필터 실행
        val result = filter.filter(exchange, capturingChain)

        // Then: Downstream 요청 헤더에 TraceId 존재
        StepVerifier.create(result)
            .verifyComplete()

        val downstreamTraceId = downstreamHeaders?.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
        downstreamTraceId.shouldNotBeBlank()
    }

    @Test
    fun `64자 초과 TraceId는 거부하고 새 UUID 생성`() {
        // Given: 65자 길이의 TraceId
        val tooLongTraceId = "a".repeat(65)
        val request = MockServerHttpRequest.get("/test")
            .header(TraceIdWebFilter.TRACE_ID_HEADER, tooLongTraceId)
            .build()
        val exchange = MockServerWebExchange.from(request)

        // When: 필터 실행
        val result = filter.filter(exchange, mockChain)

        // Then: 새로운 UUID 생성 (입력값 거부)
        StepVerifier.create(result)
            .verifyComplete()

        val responseTraceId = exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
        responseTraceId.shouldNotBeBlank()
        responseTraceId shouldNotBe tooLongTraceId
        responseTraceId!! shouldContain "-" // UUID 형식
    }

    @Test
    fun `특수문자 또는 개행 포함 TraceId는 거부하고 새 UUID 생성`() {
        // Given: 특수문자 및 개행 포함 TraceId (로그 주입 시도)
        val maliciousTraceId = "trace\nFAKE_LOG: admin access"
        val request = MockServerHttpRequest.get("/test")
            .header(TraceIdWebFilter.TRACE_ID_HEADER, maliciousTraceId)
            .build()
        val exchange = MockServerWebExchange.from(request)

        // When: 필터 실행
        val result = filter.filter(exchange, mockChain)

        // Then: 새로운 UUID 생성 (악의적 입력 거부)
        StepVerifier.create(result)
            .verifyComplete()

        val responseTraceId = exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
        responseTraceId.shouldNotBeBlank()
        responseTraceId shouldNotBe maliciousTraceId
        responseTraceId!! shouldContain "-" // UUID 형식
    }

    @Test
    fun `유효한 alphanumeric-hyphen TraceId는 그대로 유지`() {
        // Given: 유효한 패턴의 TraceId
        val validTraceId = "abc123-XYZ-456-def-789"
        val request = MockServerHttpRequest.get("/test")
            .header(TraceIdWebFilter.TRACE_ID_HEADER, validTraceId)
            .build()
        val exchange = MockServerWebExchange.from(request)

        // When: 필터 실행
        val result = filter.filter(exchange, mockChain)

        // Then: 입력값 그대로 유지
        StepVerifier.create(result)
            .verifyComplete()

        val responseTraceId = exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
        responseTraceId shouldBe validTraceId
    }
}
