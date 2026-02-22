package com.ticketqueue.gateway.config

import com.ticketqueue.gateway.BaseIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * 요청 크기 제한 통합 테스트
 *
 * - user-service 라우트: RequestSize=10KB 필터 적용 (auth, users 경로)
 * - 전역 설정: max-request-size=1MB
 *
 * REQ-GW-014 준수
 */
class RequestSizeLimitTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `auth 경로에 10KB 초과 요청 시 413 응답 반환`() {
        // 11KB (11264 bytes) > RequestSize=10KB (10240 bytes) → 413 예상
        val over10KBBody = "x".repeat(11 * 1024)

        webTestClient.post()
            .uri("/auth/signup")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(over10KBBody)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
    }

    @Test
    fun `user-service 외 경로에 1KB 요청은 크기 제한으로 거부되지 않음`() {
        // RequestSize 필터가 없는 경로에서는 크기 제한(413) 대신 JWT 인증 오류(401) 반환
        val normalBody = "x".repeat(1024)

        webTestClient.post()
            .uri("/payments")
            .contentType(MediaType.TEXT_PLAIN)
            .bodyValue(normalBody)
            .exchange()
            .expectStatus().isUnauthorized // 413이 아닌 401 → RequestSize 필터 미적용 확인
    }
}
