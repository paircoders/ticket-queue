package com.ticketqueue.event.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.dto.ErrorResponse
import com.ticketqueue.common.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Spring Security 설정
 *
 * Event Service의 보안 정책을 정의한다.
 * - CSRF 비활성화: API 서버이므로 상태 비저장(Stateless) 방식 사용
 * - 세션 미사용: JWT 기반 인증을 사용하므로 서버 세션 불필요
 * - GatewayAuthFilter를 UsernamePasswordAuthenticationFilter 앞에 등록하여
 *   API Gateway가 전달한 헤더 기반 인증을 처리
 * - 커스텀 에러 핸들러: 401/403 응답을 프로젝트 표준 ErrorResponse 형식으로 반환
 *
 * @see GatewayAuthFilter
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val gatewayAuthFilter: GatewayAuthFilter,
    private val objectMapper: ObjectMapper
) {

    /**
     * 보안 필터 체인 구성
     *
     * 엔드포인트별 권한 규칙:
     * - GET /venues/.. : 공개 (인증 불필요) - 공연장/홀 조회
     * - POST, PATCH, DELETE /venues/.. : ADMIN 권한 필요 - 공연장/홀 생성/수정/삭제
     * - /actuator/.. : 공개 (헬스체크, 모니터링용)
     * - 그 외 모든 요청: 인증 필요
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // 내부 서비스 간 통신 API - Spring Security 공개 (API Key 인증은 InternalApiAuthInterceptor에서 처리)
                    .requestMatchers("/internal/**").permitAll()
                    // 공연장/홀 조회 API - 공개
                    .requestMatchers(HttpMethod.GET, "/venues/**").permitAll()
                    // 공연장/홀 변경 API - ADMIN 전용
                    .requestMatchers(HttpMethod.POST, "/venues/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/venues/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/venues/**").hasRole("ADMIN")
                    // 공연 조회 API - 공개
                    .requestMatchers(HttpMethod.GET, "/events/**").permitAll()
                    // 공연 변경 API - ADMIN 전용
                    .requestMatchers(HttpMethod.POST, "/events/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/events/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/events/**").hasRole("ADMIN")
                    // 액추에이터 - 헬스체크/정보만 공개
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint(customAuthenticationEntryPoint())
                    .accessDeniedHandler(customAccessDeniedHandler())
            }
            .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }

    /**
     * 401 Unauthorized 에러 핸들러
     *
     * 인증 실패 시 프로젝트 표준 ErrorResponse JSON 형식으로 반환한다.
     */
    private fun customAuthenticationEntryPoint(): AuthenticationEntryPoint {
        return AuthenticationEntryPoint { request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException ->
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"

            val errorResponse = ErrorResponse.of(ErrorCode.UNAUTHORIZED)
            response.writer.write(objectMapper.writeValueAsString(errorResponse))
        }
    }

    /**
     * 403 Forbidden 에러 핸들러
     *
     * 인가 실패 시 프로젝트 표준 ErrorResponse JSON 형식으로 반환한다.
     */
    private fun customAccessDeniedHandler(): AccessDeniedHandler {
        return AccessDeniedHandler { request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException ->
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"

            val errorResponse = ErrorResponse.of(ErrorCode.FORBIDDEN)
            response.writer.write(objectMapper.writeValueAsString(errorResponse))
        }
    }
}
