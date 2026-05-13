package com.ticketqueue.payment.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.security.GatewayAuthFilter
import com.ticketqueue.common.security.SecurityErrorHandlers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
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
