package com.ticketqueue.gateway.filter

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * 모든 응답에 보안 헤더를 추가하는 WebFilter
 *
 * 추가 헤더:
 * - X-Content-Type-Options: nosniff — MIME 타입 스니핑 방지
 * - X-Frame-Options: DENY — Clickjacking 방지
 * - Strict-Transport-Security: max-age=31536000; includeSubDomains — HTTPS 강제 (HSTS)
 * - X-XSS-Protection: 0 — 최신 브라우저에서 비활성화 권장 (CSP로 대체)
 *
 * REQ-GW-011 준수
 *
 * @Order(HIGHEST_PRECEDENCE + 1): TraceIdWebFilter(HIGHEST_PRECEDENCE) 바로 다음,
 * JwtAuthenticationWebFilter(HIGHEST_PRECEDENCE + 2) 이전에 실행.
 * chain.filter() 호출 전에 응답 헤더를 설정하므로, JWT 필터가 응답을 short-circuit하는
 * 경우(401/403)에도 보안 헤더가 반드시 포함됨.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SecurityHeadersWebFilter : WebFilter {

    companion object {
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val X_FRAME_OPTIONS = "X-Frame-Options"
        const val STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security"
        const val X_XSS_PROTECTION = "X-XSS-Protection"

        const val NOSNIFF = "nosniff"
        const val DENY = "DENY"
        const val HSTS_VALUE = "max-age=31536000; includeSubDomains"
        const val XSS_DISABLED = "0"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        exchange.response.headers.apply {
            set(X_CONTENT_TYPE_OPTIONS, NOSNIFF)
            set(X_FRAME_OPTIONS, DENY)
            set(STRICT_TRANSPORT_SECURITY, HSTS_VALUE)
            set(X_XSS_PROTECTION, XSS_DISABLED)
        }
        return chain.filter(exchange)
    }
}
