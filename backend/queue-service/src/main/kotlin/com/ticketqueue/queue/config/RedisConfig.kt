package com.ticketqueue.queue.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
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

    @Bean
    fun stringRedisTemplate(connectionFactory: RedisConnectionFactory): StringRedisTemplate {
        return StringRedisTemplate(connectionFactory)
    }

    /**
     * 배치 승인 Lua 스크립트 (REQ-QUEUE-005)
     *
     * Sorted Set에서 상위 N명을 원자적으로 추출 및 제거한다.
     *
     * KEYS[1]: queue:{scheduleId}
     * ARGV[1]: batchSize (기본 10)
     * @return 승인된 userId 목록
     */
    @Bean
    fun batchApproveScript(): DefaultRedisScript<List<*>> {
        return DefaultRedisScript<List<*>>().apply {
            setLocation(ClassPathResource("lua/batch-approve.lua"))
            setResultType(List::class.java)
        }
    }

    /**
     * 대기열 진입 Lua 스크립트 (REQ-QUEUE-001, REQ-QUEUE-006, REQ-QUEUE-011)
     *
     * ZCARD, ZADD, SET 간의 Race Condition을 원자적으로 처리한다.
     *
     * KEYS[1]: queue:{scheduleId}
     * KEYS[2]: queue:active:{userId}
     * ARGV[1]: userId, ARGV[2]: timestamp, ARGV[3]: maxCapacity
     * ARGV[4]: activeUserTtl, ARGV[5]: scheduleId
     * @return [resultCode, value] 쌍
     */
    @Bean
    fun queueEnterScript(): DefaultRedisScript<List<*>> {
        return DefaultRedisScript<List<*>>().apply {
            setLocation(ClassPathResource("lua/queue-enter.lua"))
            setResultType(List::class.java)
        }
    }
}
