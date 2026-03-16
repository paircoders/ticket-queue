package com.ticketqueue.gateway.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import reactor.core.publisher.Mono

/**
 * Spring Cloud Gateway 기본 설정
 *
 * REQ-GW-005: 전역 IP 기반 Rate Limiting (replenishRate=50, burstCapacity=100)
 * REQ-GW-018: Payment 경로 전용 강화 Rate Limiting (replenishRate=1, burstCapacity=3, 사용자 기반)
 */
@Configuration
@EnableConfigurationProperties(JwtProperties::class, GatewayRateLimitProperties::class)
class GatewayConfig(
    private val rateLimitProperties: GatewayRateLimitProperties,
) {

    private fun isTrustedProxy(remoteIp: String): Boolean =
        rateLimitProperties.trustedProxyIps.contains(remoteIp)

    /**
     * 전역 IP 기반 Rate Limiter (REQ-GW-005)
     * - replenishRate=50: 초당 50개 토큰 보충
     * - burstCapacity=100: 최대 100개 버스트 허용
     * - requestedTokens=1: 요청당 1개 토큰 소비
     */
    @Bean
    @Primary
    fun globalRedisRateLimiter(): RedisRateLimiter = RedisRateLimiter(50, 100, 1)

    /**
     * Payment 경로 전용 강화 Rate Limiter (REQ-GW-018)
     * - replenishRate=1: 초당 1개 토큰 보충 (20/분 ≒ 1개/3초를 엄격하게 적용)
     * - burstCapacity=3: 최대 3개 버스트 허용
     * - requestedTokens=1: 요청당 1개 토큰 소비
     */
    @Bean
    fun paymentRedisRateLimiter(): RedisRateLimiter = RedisRateLimiter(1, 3, 1)

    /**
     * IP 기반 KeyResolver (전역 Rate Limiting용)
     *
     * remoteAddress가 trustedProxyIps에 포함된 경우에만 X-Forwarded-For 헤더의
     * 첫 번째 IP를 클라이언트 IP로 사용한다. 신뢰할 수 없는 출처의 XFF 헤더를
     * 그대로 수용하면 임의 IP로 Rate Limiting을 우회할 수 있다.
     */
    @Bean
    @Primary
    fun ipKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val remoteIp = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        val ip = if (isTrustedProxy(remoteIp)) {
            val xff = exchange.request.headers.getFirst("X-Forwarded-For")
            xff?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: remoteIp
        } else {
            remoteIp
        }
        Mono.just(ip)
    }

    /**
     * 사용자 기반 KeyResolver (Payment Rate Limiting용)
     *
     * JWT 필터가 설정한 X-User-Id 헤더를 사용.
     * 헤더 없는 경우 IP fallback (비인증 요청도 제한).
     */
    @Bean
    fun userKeyResolver(): KeyResolver = KeyResolver { exchange ->
        val userId = exchange.request.headers.getFirst("X-User-Id")
            ?: exchange.request.remoteAddress?.address?.hostAddress
            ?: "unknown"
        Mono.just(userId)
    }
}
