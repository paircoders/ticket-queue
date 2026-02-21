package com.ticketqueue.gateway.security

import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.server.ServerWebExchange

/**
 * 공개/관리자 전용 엔드포인트 판별
 *
 * REQ-GW-003 (공개 엔드포인트 허용), REQ-GW-015 (관리자 엔드포인트 인가)
 */
@Component
class RouteValidator {

    private val pathMatcher = AntPathMatcher()

    /**
     * JWT 검증 없이 통과시킬 공개 엔드포인트 (path + method 조합)
     *
     * internal 경로 포함 이유:
     * JWT 필터는 WebFilter로 게이트웨이 라우트 처리보다 먼저 실행됨.
     * 내부 API는 게이트웨이 라우트 설정(SetStatus=404)이 차단을 담당하므로,
     * JWT 필터가 개입하지 않아야 기존 404 동작이 보존됨.
     */
    private val publicRoutes: List<RouteRule> = listOf(
        RouteRule("/auth/signup", HttpMethod.POST),
        RouteRule("/auth/login", HttpMethod.POST),
        RouteRule("/auth/refresh", HttpMethod.POST),
        RouteRule("/events", HttpMethod.GET),
        RouteRule("/events/*", HttpMethod.GET),
        RouteRule("/events/schedules/*/seats", HttpMethod.GET),
        RouteRule("/venues", HttpMethod.GET),
        RouteRule("/venues/*", HttpMethod.GET),
        RouteRule("/actuator/**", null), // ANY method
        RouteRule("/internal/**", null), // 게이트웨이 라우트(404)가 차단 담당
        RouteRule("/fallback/**", null), // 서킷 브레이커 폴백 (내부 포워드 경로, 민감 데이터 없음)
    )

    /**
     * ADMIN 권한만 접근 가능한 엔드포인트 (role=ADMIN 아니면 403)
     */
    private val adminOnlyRoutes: List<RouteRule> = listOf(
        RouteRule("/events", HttpMethod.POST),
        RouteRule("/events/*", HttpMethod.PUT),
        RouteRule("/events/*", HttpMethod.DELETE),
        RouteRule("/venues", HttpMethod.POST),
        RouteRule("/venues/*", HttpMethod.PUT),
        RouteRule("/venues/*", HttpMethod.DELETE),
        RouteRule("/venues/*/halls", HttpMethod.POST),
        RouteRule("/venues/*/halls/*", HttpMethod.PUT),
        RouteRule("/venues/*/halls/*", HttpMethod.DELETE),
        RouteRule("/queue/admin/**", null), // ANY method
    )

    /**
     * 공개 엔드포인트 여부 확인 (JWT 검증 스킵)
     */
    fun isPublic(exchange: ServerWebExchange): Boolean {
        val path = exchange.request.path.value()
        val method = exchange.request.method
        return publicRoutes.any { it.matches(path, method, pathMatcher) }
    }

    /**
     * 관리자 전용 엔드포인트 여부 확인 (role=ADMIN 필수)
     */
    fun isAdminOnly(exchange: ServerWebExchange): Boolean {
        val path = exchange.request.path.value()
        val method = exchange.request.method
        return adminOnlyRoutes.any { it.matches(path, method, pathMatcher) }
    }

    private data class RouteRule(
        val pattern: String,
        val method: HttpMethod?, // null이면 모든 HTTP 메서드에 적용
    ) {
        fun matches(path: String, requestMethod: HttpMethod, matcher: AntPathMatcher): Boolean {
            if (!matcher.match(pattern, path)) return false
            return method == null || method == requestMethod
        }
    }
}
