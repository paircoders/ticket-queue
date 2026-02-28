package com.ticketqueue.event.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.serializer.GenericToStringSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import org.springframework.stereotype.Component

/**
 * Cache Stampede Lock 공통 유틸리티 컴포넌트
 *
 * EventService, ScheduleService, SeatService의 중복 tryAcquireStampedeLock 로직을 통합한다.
 *
 * 버그 수정 (REQ-EVT-021):
 * - 기존: redisTemplate.execute(script, keys, ttl.toString()) 는 기본 value serializer
 *   (GenericJackson2JsonRedisSerializer)로 args를 직렬화하여 "10" (JSON 따옴표 포함)을 전달
 *   → Lua SET NX EX 인자 파싱 실패 → catch에서 true 반환 → lock 실질적 미작동
 * - 수정: StringRedisSerializer를 argsSerializer로 명시하여 10 (raw bytes)으로 전달
 */
@Component
class CacheHelper(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val cacheStampedeLockScript: DefaultRedisScript<Long>
) {

    private val log = LoggerFactory.getLogger(CacheHelper::class.java)

    /**
     * Cache Stampede Lock 획득 시도
     *
     * Lua 스크립트로 SET NX EX 원자적 실행. 락 획득 시 true 반환.
     * Redis 장애 시 true 반환하여 DB 조회와 캐시 저장을 허용한다 (안전 방향).
     */
    fun tryAcquireStampedeLock(lockKey: String, ttlSeconds: Long): Boolean {
        return try {
            val result = redisTemplate.execute(
                cacheStampedeLockScript,
                StringRedisSerializer(),
                GenericToStringSerializer(Long::class.java),
                listOf(lockKey),
                ttlSeconds.toString()
            )
            result == 1L
        } catch (e: Exception) {
            log.warn("Stampede lock execution failed for key: $lockKey, treating as acquired", e)
            true
        }
    }
}
