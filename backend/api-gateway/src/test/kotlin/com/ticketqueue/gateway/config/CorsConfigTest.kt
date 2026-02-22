package com.ticketqueue.gateway.config

import com.ticketqueue.gateway.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * CORS 설정 통합 테스트
 *
 * application.yml `globalcors` 설정 검증:
 * - 허용된 Origin: https://ticketing.vercel.app
 * - 허용 메서드: GET, POST, PUT, DELETE, OPTIONS
 * - 허용 헤더: Authorization, Content-Type, X-Queue-Token
 * - 노출 헤더: X-Queue-Token
 * - allow-credentials: true
 * - max-age: 3600
 *
 * REQ-GW-004 준수
 */
class CorsConfigTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `허용된 Origin 요청 시 Access-Control-Allow-Origin 헤더 반환`() {
        webTestClient.get()
            .uri("/events")
            .header("Origin", "https://ticketing.vercel.app")
            .exchange()
            .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://ticketing.vercel.app")
    }

    @Test
    fun `허용되지 않은 Origin 요청 시 Access-Control-Allow-Origin 헤더 부재`() {
        webTestClient.get()
            .uri("/events")
            .header("Origin", "https://evil.com")
            .exchange()
            .expectHeader().doesNotExist("Access-Control-Allow-Origin")
    }

    @Test
    fun `OPTIONS preflight 요청 시 200 응답과 Access-Control-Allow-Methods 헤더 반환`() {
        webTestClient.options()
            .uri("/events")
            .header("Origin", "https://ticketing.vercel.app")
            .header("Access-Control-Request-Method", "GET")
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists("Access-Control-Allow-Methods")
    }

    @Test
    fun `허용된 Origin 요청 시 Access-Control-Allow-Credentials true 반환`() {
        webTestClient.get()
            .uri("/events")
            .header("Origin", "https://ticketing.vercel.app")
            .exchange()
            .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
    }

    @Test
    fun `허용된 Origin 요청 시 Access-Control-Expose-Headers에 X-Queue-Token 포함`() {
        webTestClient.get()
            .uri("/events")
            .header("Origin", "https://ticketing.vercel.app")
            .exchange()
            .expectHeader().valueEquals("Access-Control-Expose-Headers", "X-Queue-Token")
    }

    @Test
    fun `OPTIONS preflight 요청 시 Access-Control-Max-Age 3600 반환`() {
        webTestClient.options()
            .uri("/events")
            .header("Origin", "https://ticketing.vercel.app")
            .header("Access-Control-Request-Method", "GET")
            .exchange()
            .expectHeader().valueEquals("Access-Control-Max-Age", "3600")
    }
}
