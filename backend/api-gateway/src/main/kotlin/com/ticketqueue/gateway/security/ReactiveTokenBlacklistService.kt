package com.ticketqueue.gateway.security

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Redis에서 Access Token 블랙리스트 여부 확인
 *
 * User Service 로그아웃 시 `token:blacklist:{jti}` 키를 Redis에 저장.
 * Gateway는 해당 키 존재 여부만 확인하여 폐기된 토큰 차단.
 *
 * WebFlux 환경에서 non-blocking I/O를 위해 ReactiveStringRedisTemplate 사용.
 */
@Component
class ReactiveTokenBlacklistService(
    private val redisTemplate: ReactiveStringRedisTemplate,
) {

    companion object {
        private const val BLACKLIST_KEY_PREFIX = "token:blacklist:"
    }

    /**
     * jti(JWT ID)로 블랙리스트 등록 여부 확인
     *
     * @return true면 블랙리스트에 등록된 토큰 (요청 거부 필요)
     */
    fun isBlacklisted(jti: String): Mono<Boolean> =
        redisTemplate.hasKey("$BLACKLIST_KEY_PREFIX$jti")
}
