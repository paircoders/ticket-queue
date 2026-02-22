package com.ticketqueue.gateway.filter

import com.ticketqueue.gateway.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * SecurityHeadersWebFilter 통합 테스트
 *
 * 모든 응답에 보안 헤더가 포함되는지 검증
 * TraceIdWebFilterTest와 동일한 패턴 (BaseIntegrationTest 상속, WebTestClient 사용)
 */
class SecurityHeadersWebFilterTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `모든 응답에 X-Content-Type-Options nosniff 헤더 존재`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_CONTENT_TYPE_OPTIONS,
                SecurityHeadersWebFilter.NOSNIFF
            )
    }

    @Test
    fun `모든 응답에 X-Frame-Options DENY 헤더 존재`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_FRAME_OPTIONS,
                SecurityHeadersWebFilter.DENY
            )
    }

    @Test
    fun `모든 응답에 Strict-Transport-Security 헤더 존재`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.STRICT_TRANSPORT_SECURITY,
                SecurityHeadersWebFilter.HSTS_VALUE
            )
    }

    @Test
    fun `모든 응답에 X-XSS-Protection 0 헤더 존재`() {
        webTestClient.get()
            .uri("/events")
            .exchange()
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_XSS_PROTECTION,
                SecurityHeadersWebFilter.XSS_DISABLED
            )
    }

    @Test
    fun `internal 경로 404 응답에도 보안 헤더 존재`() {
        webTestClient.get()
            .uri("/internal/seats/status/1")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_CONTENT_TYPE_OPTIONS,
                SecurityHeadersWebFilter.NOSNIFF
            )
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_FRAME_OPTIONS,
                SecurityHeadersWebFilter.DENY
            )
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.STRICT_TRANSPORT_SECURITY,
                SecurityHeadersWebFilter.HSTS_VALUE
            )
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_XSS_PROTECTION,
                SecurityHeadersWebFilter.XSS_DISABLED
            )
    }

    @Test
    fun `인증 필요 경로 401 응답에도 보안 헤더 존재`() {
        webTestClient.post()
            .uri("/payments")
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_CONTENT_TYPE_OPTIONS,
                SecurityHeadersWebFilter.NOSNIFF
            )
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_FRAME_OPTIONS,
                SecurityHeadersWebFilter.DENY
            )
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.STRICT_TRANSPORT_SECURITY,
                SecurityHeadersWebFilter.HSTS_VALUE
            )
            .expectHeader().valueEquals(
                SecurityHeadersWebFilter.X_XSS_PROTECTION,
                SecurityHeadersWebFilter.XSS_DISABLED
            )
    }
}
