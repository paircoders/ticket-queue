package com.ticketqueue.queue.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.exception.QueueException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 대기열 진입 전 회차 유효성 검증 컴포넌트 (REQ-QUEUE-001 보강)
 *
 * Event Service 내부 API를 호출하여 회차의 티켓 판매 가능 여부를 확인한다.
 * Redis 캐시(TTL 30초)를 활용해 반복 호출 비용을 최소화한다.
 *
 * Fail-closed 정책: Event Service 호출 실패 시 대기열 진입을 차단한다.
 * (유령 대기열 생성을 허용하는 것보다 일시적 차단이 더 안전)
 */
@Component
class ScheduleValidator(
    private val eventServiceClient: EventServiceClient,
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {

    private val logger = KotlinLogging.logger {}

    companion object {
        private const val CACHE_KEY_PREFIX = "schedule:sellable:"
        private val CACHE_TTL = Duration.ofSeconds(30)
    }

    /**
     * 회차가 티켓 판매 가능한 상태인지 검증한다.
     *
     * @throws QueueException(SCHEDULE_NOT_FOUND) 존재하지 않는 scheduleId
     * @throws QueueException(TICKET_SALE_NOT_STARTED) 판매 시작 전
     * @throws QueueException(TICKET_SALE_ENDED) 판매 종료 후
     * @throws QueueException(INTERNAL_SERVER_ERROR) Event Service 호출 실패
     */
    fun validateSchedule(scheduleId: UUID) {
        val cacheKey = "$CACHE_KEY_PREFIX$scheduleId"

        // 1. Redis 캐시 조회
        val cached = try {
            stringRedisTemplate.opsForValue().get(cacheKey)
        } catch (e: Exception) {
            logger.warn(e) { "Redis cache read failed for schedule sellable: scheduleId=$scheduleId" }
            null
        }

        if (cached != null) {
            try {
                val response = objectMapper.readValue(cached, EventServiceClient.SellableResponse::class.java)
                if (response.sellable) return
                throwValidationError(response.reason)
            } catch (e: JsonProcessingException) {
                logger.warn(e) { "Corrupted JSON in cache for schedule sellable: scheduleId=$scheduleId — treating as cache miss" }
                try {
                    stringRedisTemplate.delete(cacheKey)
                } catch (deleteEx: Exception) {
                    logger.warn(deleteEx) { "Failed to delete corrupted cache entry: scheduleId=$scheduleId" }
                }
            }
        }

        // 2. Event Service 호출 (캐시 미스)
        val response = try {
            eventServiceClient.checkSellable(scheduleId)
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.RESOURCE_NOT_FOUND || e.errorCode == ErrorCode.SCHEDULE_NOT_FOUND) {
                throw QueueException(ErrorCode.SCHEDULE_NOT_FOUND)
            }
            logger.error(e) { "Event service call failed (fail-closed): scheduleId=$scheduleId" }
            throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
        } catch (e: Exception) {
            logger.error(e) { "Event service call failed (fail-closed): scheduleId=$scheduleId" }
            throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
        }

        // 3. 결과 캐시 저장 (TTL 30초)
        try {
            val json = objectMapper.writeValueAsString(response)
            stringRedisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL)
        } catch (e: Exception) {
            logger.warn(e) { "Redis cache write failed for schedule sellable: scheduleId=$scheduleId" }
        }

        // 4. 검증
        if (!response.sellable) {
            throwValidationError(response.reason)
        }
    }

    private fun throwValidationError(reason: String?) {
        when (reason) {
            "TICKET_SALE_NOT_STARTED" -> throw QueueException(ErrorCode.TICKET_SALE_NOT_STARTED)
            "TICKET_SALE_ENDED" -> throw QueueException(ErrorCode.TICKET_SALE_ENDED)
            "SCHEDULE_NOT_AVAILABLE" -> throw QueueException(ErrorCode.SCHEDULE_NOT_FOUND)
            else -> {
                logger.warn { "Unknown sellable reason: $reason — defaulting to SCHEDULE_NOT_FOUND" }
                throw QueueException(ErrorCode.SCHEDULE_NOT_FOUND)
            }
        }
    }
}
