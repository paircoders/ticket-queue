package com.ticketqueue.event.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.scripting.support.ResourceScriptSource

@Configuration
@EnableConfigurationProperties(CacheProperties::class)
class RedisConfig {

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
        return RedisTemplate<String, Any>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = StringRedisSerializer()
            valueSerializer = GenericJackson2JsonRedisSerializer()
            hashKeySerializer = StringRedisSerializer()
            hashValueSerializer = GenericJackson2JsonRedisSerializer()
        }
    }

    /**
     * Cache Stampede 방지 Lua 스크립트 빈 등록 (REQ-EVT-021)
     *
     * SET NX EX 원자적 락 획득 — 동시 다발적 캐시 Miss 시 단 하나의 요청만 DB 조회를 수행한다.
     */
    @Bean
    fun cacheStampedeLockScript(): DefaultRedisScript<Long> {
        return DefaultRedisScript<Long>().apply {
            setScriptSource(ResourceScriptSource(ClassPathResource("lua/cache-stampede-lock.lua")))
            resultType = Long::class.java
        }
    }
}
