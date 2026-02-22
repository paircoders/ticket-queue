package com.ticketqueue.gateway.security

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/**
 * RouteValidator 단위 테스트
 *
 * 각 라우트 목록(publicRoutes, queueTokenRequiredRoutes, adminOnlyRoutes)과
 * Ant 패턴 매칭 로직을 직접 검증.
 */
class RouteValidatorTest {

    private val routeValidator = RouteValidator()

    private fun exchange(method: HttpMethod, path: String): MockServerWebExchange {
        val builder = when (method) {
            HttpMethod.GET -> MockServerHttpRequest.get(path)
            HttpMethod.POST -> MockServerHttpRequest.post(path)
            HttpMethod.PUT -> MockServerHttpRequest.put(path)
            HttpMethod.DELETE -> MockServerHttpRequest.delete(path)
            HttpMethod.OPTIONS -> MockServerHttpRequest.options(path)
            else -> error("Unsupported HTTP method: $method")
        }
        return MockServerWebExchange.from(builder.build())
    }

    // ─── isQueueTokenRequired: positive (5개 필수 경로 모두) ────────────

    @Test
    fun `GET reservations-seats-id는 Queue Token 필수`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.GET, "/reservations/seats/123")) shouldBe true
    }

    @Test
    fun `POST reservations-hold는 Queue Token 필수`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.POST, "/reservations/hold")) shouldBe true
    }

    @Test
    fun `PUT reservations-hold-id는 Queue Token 필수`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.PUT, "/reservations/hold/456")) shouldBe true
    }

    @Test
    fun `POST payments는 Queue Token 필수`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.POST, "/payments")) shouldBe true
    }

    @Test
    fun `POST payments-confirm는 Queue Token 필수`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.POST, "/payments/confirm")) shouldBe true
    }

    // ─── isQueueTokenRequired: negative ────────────────────────────────

    @Test
    fun `GET reservations는 Queue Token 불필요`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.GET, "/reservations")) shouldBe false
    }

    @Test
    fun `DELETE reservations-hold-id는 Queue Token 불필요`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.DELETE, "/reservations/hold/123")) shouldBe false
    }

    @Test
    fun `GET payments-id는 Queue Token 불필요`() {
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.GET, "/payments/123")) shouldBe false
    }

    // ─── isQueueTokenRequired: 경계값 ──────────────────────────────────

    @Test
    fun `GET reservations-seats (path variable 없음)는 Queue Token 불필요`() {
        // queueTokenRequiredRoutes 패턴: /reservations/seats/* (와일드카드 필수)
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.GET, "/reservations/seats")) shouldBe false
    }

    @Test
    fun `POST payments-confirm-extra (추가 세그먼트)는 Queue Token 불필요`() {
        // payments/confirm 이후 추가 세그먼트는 패턴 불일치
        routeValidator.isQueueTokenRequired(exchange(HttpMethod.POST, "/payments/confirm/extra")) shouldBe false
    }

    // ─── isPublic: positive ────────────────────────────────────────────

    @Test
    fun `POST auth-login은 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/auth/login")) shouldBe true
    }

    @Test
    fun `POST auth-signup은 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/auth/signup")) shouldBe true
    }

    @Test
    fun `POST auth-refresh는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/auth/refresh")) shouldBe true
    }

    @Test
    fun `GET events는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/events")) shouldBe true
    }

    @Test
    fun `GET events-id는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/events/123")) shouldBe true
    }

    @Test
    fun `GET actuator-health는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/actuator/health")) shouldBe true
    }

    @Test
    fun `GET internal 경로는 공개 엔드포인트 (Gateway 라우트가 404 차단 담당)`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/internal/seats/status/1")) shouldBe true
    }

    // ─── isPublic: negative ────────────────────────────────────────────

    @Test
    fun `GET reservations는 공개 엔드포인트 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/reservations")) shouldBe false
    }

    @Test
    fun `GET users-me는 공개 엔드포인트 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/users/me")) shouldBe false
    }

    @Test
    fun `POST auth-logout은 공개 엔드포인트 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/auth/logout")) shouldBe false
    }

    // ─── isAdminOnly: positive ─────────────────────────────────────────

    @Test
    fun `POST events는 관리자 전용`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/events")) shouldBe true
    }

    @Test
    fun `PUT events-id는 관리자 전용`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.PUT, "/events/123")) shouldBe true
    }

    @Test
    fun `DELETE events-id는 관리자 전용`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.DELETE, "/events/123")) shouldBe true
    }

    @Test
    fun `POST venues는 관리자 전용`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/venues")) shouldBe true
    }

    @Test
    fun `GET queue-admin-stats는 관리자 전용`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/queue/admin/stats")) shouldBe true
    }

    // ─── isAdminOnly: negative ─────────────────────────────────────────

    @Test
    fun `GET events는 관리자 전용 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/events")) shouldBe false
    }

    @Test
    fun `GET reservations는 관리자 전용 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/reservations")) shouldBe false
    }

    @Test
    fun `POST auth-login은 관리자 전용 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/auth/login")) shouldBe false
    }
}
