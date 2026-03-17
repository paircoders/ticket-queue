package com.ticketqueue.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Rate Limiting 설정 프로퍼티
 *
 * trustedProxyIps: X-Forwarded-For 헤더를 신뢰할 프록시 IP 목록.
 * 빈 경우 XFF 헤더를 무시하고 remoteAddress를 직접 사용한다.
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
data class GatewayRateLimitProperties(
    val trustedProxyIps: Set<String> = emptySet(),
)
