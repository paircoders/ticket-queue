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

    /**
     * 대기열 상태 조회 Lua 스크립트 (REQ-QUEUE-002)
     *
     * 토큰 발급 여부(ACTIVE)와 대기 순위(WAITING)를 단일 round-trip으로 확인한다.
     *
     * KEYS[1]: queue:{scheduleId}
     * KEYS[2]: queue:user-token:{userId}:{scheduleId}
     * ARGV[1]: userId
     * @return [resultCode, value] 쌍
     *   {0, rank}  - WAITING (rank = 0-based ZRANK)
     *   {1, token} - ACTIVE (token 문자열)
     *   {2, -1}    - NOT_IN_QUEUE
     */
    @Bean
    fun queueStatusScript(): DefaultRedisScript<List<*>> {
        return DefaultRedisScript<List<*>>().apply {
            setLocation(ClassPathResource("lua/queue-status.lua"))
            setResultType(List::class.java)
        }
    }

    /**
     * Rate Limit 검사 Lua 스크립트 (REQ-QUEUE-008)
     *
     * 고정 윈도우 방식으로 사용자별 요청 횟수를 원자적으로 카운트한다.
     *
     * KEYS[1]: rate:queue-status:{userId}
     * ARGV[1]: maxRequests, ARGV[2]: windowSeconds
     * @return 0 (허용) / 1 (한도 초과)
     */
    @Bean
    fun rateLimitScript(): DefaultRedisScript<Long> {
        return DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("lua/rate-limit.lua"))
            setResultType(Long::class.java)
        }
    }
}
