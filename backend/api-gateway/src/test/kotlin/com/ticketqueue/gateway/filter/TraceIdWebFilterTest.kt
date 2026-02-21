package com.ticketqueue.gateway.filter

import com.ticketqueue.gateway.BaseIntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * TraceIdWebFilter 통합 테스트
 *
 * 실제 Gateway 컨텍스트에서 TraceId 전파 동작 검증
 * InternalPathBlockFilterTest와 동일한 패턴 (BaseIntegrationTest 상속, WebTestClient 사용)
 */
class TraceIdWebFilterTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `헤더 없이 요청하면 응답에 UUID 형식 TraceId 반환`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectStatus().is5xxServerError  // Downstream 없어서 500, but 헤더는 확인 가능
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
            .expectHeader().value(TraceIdWebFilter.TRACE_ID_HEADER) { traceId ->
                traceId.shouldNotBeBlank()
                traceId shouldContain "-"  // UUID 형식 검증
            }
    }

    @Test
    fun `기존 TraceId 헤더가 있으면 응답에 그대로 반환`() {
        val existingTraceId = "my-custom-trace-id-12345"

        webTestClient.get()
            .uri("/events")
            .header(TraceIdWebFilter.TRACE_ID_HEADER, existingTraceId)
            .exchange()
            .expectStatus().is5xxServerError
            .expectHeader().valueEquals(TraceIdWebFilter.TRACE_ID_HEADER, existingTraceId)
    }

    @Test
    fun `internal 경로에도 TraceId 적용 (404 응답에도 헤더 존재)`() {
        webTestClient.get()
            .uri("/internal/seats/status/1")
            .exchange()
            .expectStatus().isNotFound  // InternalPathBlockFilter가 404 반환
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
            .expectHeader().value(TraceIdWebFilter.TRACE_ID_HEADER) { traceId ->
                traceId.shouldNotBeBlank()
            }
    }

    @Test
    fun `요청마다 고유한 TraceId 생성`() {
        // 첫 번째 요청
        val firstTraceId = webTestClient.get()
            .uri("/events")
            .exchange()
            .expectStatus().is5xxServerError
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
            .returnResult(String::class.java)
            .responseHeaders
            .getFirst(TraceIdWebFilter.TRACE_ID_HEADER)

        // 두 번째 요청
        val secondTraceId = webTestClient.get()
            .uri("/events")
            .exchange()
            .expectStatus().is5xxServerError
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
            .returnResult(String::class.java)
            .responseHeaders
            .getFirst(TraceIdWebFilter.TRACE_ID_HEADER)

        // 두 TraceId는 달라야 함
        firstTraceId.shouldNotBeBlank()
        secondTraceId.shouldNotBeBlank()
        firstTraceId shouldNotBe secondTraceId
    }

    @Test
    fun `POST 요청에도 TraceId 적용`() {
        webTestClient.post()
            .uri("/auth/login")
            .exchange()
            .expectStatus().is5xxServerError
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
    }

    @Test
    fun `PUT 요청에도 TraceId 적용`() {
        // PUT /reservations/1은 인증 필요 → JWT 필터가 401 반환
        // TraceId 필터는 JWT 필터보다 먼저 실행되므로 401 응답에도 TraceId 헤더 존재
        webTestClient.put()
            .uri("/reservations/1")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
    }

    @Test
    fun `DELETE 요청에도 TraceId 적용`() {
        // DELETE /reservations/1은 인증 필요 → JWT 필터가 401 반환
        // TraceId 필터는 JWT 필터보다 먼저 실행되므로 401 응답에도 TraceId 헤더 존재
        webTestClient.delete()
            .uri("/reservations/1")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
    }

    @Test
    fun `쿼리 파라미터가 있는 요청에도 TraceId 적용`() {
        webTestClient.get()
            .uri("/events?page=1&size=10")
            .exchange()
            .expectStatus().is5xxServerError
            .expectHeader().exists(TraceIdWebFilter.TRACE_ID_HEADER)
    }
}
