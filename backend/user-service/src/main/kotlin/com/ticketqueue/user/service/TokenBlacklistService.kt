package com.ticketqueue.user.service

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class TokenBlacklistService(private val stringRedisTemplate: StringRedisTemplate) {

    fun addToBlacklist(jti: String, ttlMillis: Long) {
        stringRedisTemplate.opsForValue().set(
            "token:blacklist:$jti", "1", Duration.ofMillis(ttlMillis)
        )
    }
}
