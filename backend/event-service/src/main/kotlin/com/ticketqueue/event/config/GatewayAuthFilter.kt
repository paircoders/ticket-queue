package com.ticketqueue.event.config

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
 * 3. API Gateway -> Event Service: 헤더 포함 요청 전달
 * 4. 이 필터: 헤더를 읽어 SecurityContext에 인증 정보 설정
 *
 * 헤더가 없는 경우(비인증 요청) SecurityContext를 설정하지 않으며,
 * SecurityConfig의 permitAll() 규칙에 의해 공개 API 접근이 허용된다.
 */
@Component
class GatewayAuthFilter : OncePerRequestFilter() {

    /**
     * 요청마다 한 번 실행되는 필터 로직
     *
     * X-User-Id와 X-User-Role 헤더가 모두 존재할 때만 인증 객체를 생성한다.
     * Role 값에 "ROLE_" 접두사를 붙여 Spring Security 권한 형식에 맞춘다.
     * (예: "ADMIN" -> "ROLE_ADMIN")
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val userId = request.getHeader("X-User-Id")
        val userRole = request.getHeader("X-User-Role")

        if (userId != null && userRole != null) {
            // Spring Security 권한 형식으로 변환 후 인증 객체 생성
            val authorities = listOf(SimpleGrantedAuthority("ROLE_$userRole"))
            val authentication = UsernamePasswordAuthenticationToken(userId, null, authorities)
            SecurityContextHolder.getContext().authentication = authentication
        }

        filterChain.doFilter(request, response)
    }
}
