package com.ticketqueue.gateway.controller

import com.ticketqueue.gateway.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * FallbackController 통합 테스트
 *
 * 실제 Spring 컨텍스트에서 /fallback/{service} 엔드포인트의 응답을 검증합니다.
 * /fallback/{service} 는 RouteValidator에 public 경로로 등록되어 있어 JWT 없이 직접 호출 가능합니다.
 */
class FallbackControllerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    // ─── GET 요청 ─────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["user", "event", "queue", "reservation", "payment"])
    fun `GET fallback 호출 시 503과 JSON 응답 반환`(serviceName: String) {
        webTestClient.get()
            .uri("/fallback/$serviceName")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
    }

    // ─── POST 요청 ────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["user", "event", "queue", "reservation", "payment"])
    fun `POST fallback 호출 시 503과 JSON 응답 반환`(serviceName: String) {
        webTestClient.post()
            .uri("/fallback/$serviceName")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
    }

    // ─── 응답 body 구조 ───────────────────────────────────────────────

    @Test
    fun `응답 body에 code, message, timestamp, traceId가 포함되어야 한다`() {
        webTestClient.get()
            .uri("/fallback/user")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
            .jsonPath("$.message").isNotEmpty
            .jsonPath("$.timestamp").isNotEmpty
            .jsonPath("$.traceId").isNotEmpty
    }

    @Test
    fun `Content-Type은 application-json이어야 한다`() {
        webTestClient.get()
            .uri("/fallback/event")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
    }
}
