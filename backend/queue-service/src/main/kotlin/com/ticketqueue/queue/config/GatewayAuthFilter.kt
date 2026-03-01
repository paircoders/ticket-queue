package com.ticketqueue.queue.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * API Gateway 인증 헤더 기반 필터
 *
 * API Gateway에서 JWT 검증 후 전달하는 사용자 정보 헤더를 파싱하여
 * Spring Security의 SecurityContext에 인증 객체를 설정한다.
 *
 * 동작 흐름:
 * 1. 클라이언트 -> API Gateway: JWT 토큰 전송
 * 2. API Gateway: JWT 검증 후 X-User-Id, X-User-Role 헤더 추가
 * 3. API Gateway -> Queue Service: 헤더 포함 요청 전달
 * 4. 이 필터: 헤더를 읽어 SecurityContext에 인증 정보 설정
 *
 * 헤더가 없는 경우(비인증 요청) SecurityContext를 설정하지 않으며,
 * SecurityConfig의 authenticated() 규칙에 의해 401이 반환된다.
 */
@Component
class GatewayAuthFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val userId = request.getHeader("X-User-Id")
        val userRole = request.getHeader("X-User-Role")

        if (userId != null && userRole != null) {
            val authorities = listOf(SimpleGrantedAuthority("ROLE_$userRole"))
            val authentication = UsernamePasswordAuthenticationToken(userId, null, authorities)
            SecurityContextHolder.getContext().authentication = authentication
        }

        filterChain.doFilter(request, response)
    }
}
