package com.ticketqueue.gateway.security

import com.ticketqueue.gateway.support.exchange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod

/**
 * RouteValidator 단위 테스트
 *
 * REQ-GW-015: Admin 엔드포인트 권한 검증
 * RouteValidator는 순수 컴포넌트이므로 mock 불필요.
 * 각 라우트 목록(publicRoutes, queueTokenRequiredRoutes, adminOnlyRoutes)과
 * Ant 패턴 매칭 로직을 직접 검증.
 */
class RouteValidatorTest {

    private val routeValidator = RouteValidator()

    // --- isAdminOnly() Positive: admin 경로 매칭 (10개) ---

    @Test
    fun `POST events는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/events")) shouldBe true
    }

    @Test
    fun `PUT events-id는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.PUT, "/events/123")) shouldBe true
    }

    @Test
    fun `DELETE events-id는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.DELETE, "/events/123")) shouldBe true
    }

    @Test
    fun `POST venues는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/venues")) shouldBe true
    }

    @Test
    fun `PUT venues-id는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.PUT, "/venues/456")) shouldBe true
    }

    @Test
    fun `DELETE venues-id는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.DELETE, "/venues/456")) shouldBe true
    }

    @Test
    fun `POST venues-id-halls는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/venues/1/halls")) shouldBe true
    }

    @Test
    fun `PUT venues-id-halls-id는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.PUT, "/venues/1/halls/2")) shouldBe true
    }

    @Test
    fun `DELETE venues-id-halls-id는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.DELETE, "/venues/1/halls/2")) shouldBe true
    }

    @Test
    fun `GET queue-admin-stats는 관리자 전용 엔드포인트`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/queue/admin/stats")) shouldBe true
    }

    // --- isAdminOnly() Negative: 비-admin 경로 ---

    @Test
    fun `GET events는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/events")) shouldBe false
    }

    @Test
    fun `GET events-id는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/events/123")) shouldBe false
    }

    @Test
    fun `GET venues는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/venues")) shouldBe false
    }

    @Test
    fun `GET venues-id는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/venues/456")) shouldBe false
    }

    @Test
    fun `POST reservations-hold는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/reservations/hold")) shouldBe false
    }

    @Test
    fun `POST queue-enter는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/queue/enter")) shouldBe false
    }

    @Test
    fun `GET queue-status는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/queue/status")) shouldBe false
    }

    @Test
    fun `GET events-schedules-id-seats는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/events/schedules/1/seats")) shouldBe false
    }

    @Test
    fun `GET reservations는 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.GET, "/reservations")) shouldBe false
    }

    @Test
    fun `POST auth-login은 관리자 전용이 아님`() {
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/auth/login")) shouldBe false
    }

    // --- isAdminOnly() 경계 케이스 (4개) ---

    @Test
    fun `POST queue-admin 하위 경로는 관리자 전용`() {
        // /queue/admin/** 패턴: ** glob으로 깊은 경로까지 매칭
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/queue/admin/some/nested")) shouldBe true
    }

    @Test
    fun `PUT events는 관리자 전용이 아님`() {
        // /events/* 패턴: * 는 세그먼트 필수 (/events 단독은 매칭 안 됨)
        routeValidator.isAdminOnly(exchange(HttpMethod.PUT, "/events")) shouldBe false
    }

    @Test
    fun `DELETE events는 관리자 전용이 아님`() {
        // /events/* 패턴: * 는 세그먼트 필수 (/events 단독은 매칭 안 됨)
        routeValidator.isAdminOnly(exchange(HttpMethod.DELETE, "/events")) shouldBe false
    }

    @Test
    fun `DELETE queue-leave는 관리자 전용이 아님`() {
        // /queue/admin/** 과 무관한 일반 queue 경로
        routeValidator.isAdminOnly(exchange(HttpMethod.DELETE, "/queue/leave")) shouldBe false
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

    // --- isPublic() Positive: 공개 경로 ---

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
    fun `GET events-schedules-id-seats는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/events/schedules/456/seats")) shouldBe true
    }

    @Test
    fun `GET venues는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/venues")) shouldBe true
    }

    @Test
    fun `GET venues-id는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/venues/123")) shouldBe true
    }

    @Test
    fun `GET actuator-health는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/actuator/health")) shouldBe true
    }

    @Test
    fun `GET internal-seats-status는 공개 엔드포인트 (Gateway 라우트가 404 담당)`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/internal/seats/status/1")) shouldBe true
    }

    @Test
    fun `GET fallback-circuit-breaker는 공개 엔드포인트`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/fallback/circuit-breaker")) shouldBe true
    }

    // --- isPublic() Negative: 인증 필수 경로 ---

    @Test
    fun `POST reservations-hold는 공개 엔드포인트가 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/reservations/hold")) shouldBe false
    }

    @Test
    fun `POST queue-enter는 공개 엔드포인트가 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/queue/enter")) shouldBe false
    }

    @Test
    fun `GET users-me는 공개 엔드포인트가 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/users/me")) shouldBe false
    }

    @Test
    fun `POST payments는 공개 엔드포인트가 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/payments")) shouldBe false
    }

    @Test
    fun `GET reservations는 공개 엔드포인트가 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.GET, "/reservations")) shouldBe false
    }

    @Test
    fun `POST auth-logout은 공개 엔드포인트가 아님`() {
        routeValidator.isPublic(exchange(HttpMethod.POST, "/auth/logout")) shouldBe false
    }

    // --- isPublic() / isAdminOnly() 교차 검증 (2개) ---

    @Test
    fun `GET events는 공개이고 관리자 전용이 아님`() {
        val exchange = exchange(HttpMethod.GET, "/events")
        routeValidator.isPublic(exchange) shouldBe true
        routeValidator.isAdminOnly(exchange) shouldBe false
    }

    @Test
    fun `POST events는 공개가 아니고 관리자 전용`() {
        val exchange = exchange(HttpMethod.POST, "/events")
        routeValidator.isPublic(exchange) shouldBe false
        routeValidator.isAdminOnly(exchange) shouldBe true
    }

    // --- 경계 케이스: 일반 인증 경로 및 URL 형식 이상 (3개) ---

    @Test
    fun `POST reservations-hold는 공개도 관리자 전용도 아닌 인증 필요 경로`() {
        val exchange = exchange(HttpMethod.POST, "/reservations/hold")
        routeValidator.isPublic(exchange) shouldBe false
        routeValidator.isAdminOnly(exchange) shouldBe false
    }

    @Test
    fun `POST events-trailing-slash는 관리자 전용이 아님 (AntPathMatcher 패턴 비매칭)`() {
        // /events 패턴은 /events/ 와 불일치, /events/* 는 메서드가 PUT/DELETE 로만 정의됨
        routeValidator.isAdminOnly(exchange(HttpMethod.POST, "/events/")) shouldBe false
    }

    @Test
    fun `PUT events-double-slash-id는 관리자 전용으로 처리됨 (Spring 경로 정규화로 admin 매칭)`() {
        // Spring이 /events//123 을 /events/123 으로 정규화하므로 /events/* 패턴에 매칭됨
        routeValidator.isAdminOnly(exchange(HttpMethod.PUT, "/events//123")) shouldBe true
    }
}
