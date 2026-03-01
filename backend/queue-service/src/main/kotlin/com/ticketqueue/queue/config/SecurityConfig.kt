package com.ticketqueue.queue.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.dto.ErrorResponse
import com.ticketqueue.common.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
 * Queue Service의 보안 정책을 정의한다.
 * - CSRF 비활성화: Stateless API 서버
 * - 세션 미사용: Gateway 헤더 기반 인증
 * - /actuator/health, /actuator/info: 공개 (헬스체크/모니터링)
 * - 그 외 모든 요청: 인증 필요 (X-User-Id + X-User-Role 헤더)
 * - 커스텀 에러 핸들러: 표준 ErrorResponse 형식으로 401/403 반환
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val gatewayAuthFilter: GatewayAuthFilter,
    private val objectMapper: ObjectMapper
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
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

    private fun customAuthenticationEntryPoint(): AuthenticationEntryPoint {
        return AuthenticationEntryPoint { _: HttpServletRequest, response: HttpServletResponse, _: AuthenticationException ->
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write(objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.UNAUTHORIZED)))
        }
    }

    private fun customAccessDeniedHandler(): AccessDeniedHandler {
        return AccessDeniedHandler { _: HttpServletRequest, response: HttpServletResponse, _: AccessDeniedException ->
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write(objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.FORBIDDEN)))
        }
    }
}
