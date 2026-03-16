package com.ticketqueue.queue.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.security.GatewayAuthFilter
import com.ticketqueue.common.security.SecurityErrorHandlers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
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
@EnableMethodSecurity
class SecurityConfig(
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
                    .authenticationEntryPoint(SecurityErrorHandlers.authenticationEntryPoint(objectMapper))
                    .accessDeniedHandler(SecurityErrorHandlers.accessDeniedHandler(objectMapper))
            }
            .addFilterBefore(GatewayAuthFilter(), UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }
}
