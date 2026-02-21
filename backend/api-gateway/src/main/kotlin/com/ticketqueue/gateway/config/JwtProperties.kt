package com.ticketqueue.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * JWT 설정 프로퍼티
 *
 * API Gateway에서는 서명 검증만 필요하므로 secret만 바인딩.
 * 토큰 발급(expiry 등)은 User Service 담당.
 */
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    val secret: String,
)
