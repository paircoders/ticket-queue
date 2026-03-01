package com.ticketqueue.queue.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.config.QueueProperties
import com.ticketqueue.queue.dto.QueueDto
import com.ticketqueue.queue.dto.QueueStatus
import com.ticketqueue.queue.exception.QueueException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 대기열 비즈니스 로직 서비스
 *
 * Lua 스크립트를 통해 원자적으로 대기열 진입을 처리하며,
 * Lua 반환 코드를 도메인 응답 또는 QueueException으로 변환한다.
 */
@Service
class QueueService(
    private val stringRedisTemplate: StringRedisTemplate,
    private val queueEnterScript: DefaultRedisScript<List<*>>,
    private val queueProperties: QueueProperties
) {

    /**
     * 대기열 진입 처리 (REQ-QUEUE-001)
     *
     * Lua 스크립트 반환 코드:
     * - 0: 신규 진입 성공
     * - 1: 동일 회차 중복 진입 (멱등성 — 기존 순위 반환)
     * - 2: 다른 회차 대기 중 → ALREADY_IN_QUEUE
     * - 3: 대기열 가득 참 → QUEUE_FULL
     */
    fun enterQueue(userId: UUID, scheduleId: UUID): QueueDto.EnterResponse {
        val queueKey = "queue:$scheduleId"
        val activeKey = "queue:active:$userId"
        val keys = listOf(queueKey, activeKey)

        val args = arrayOf(
            userId.toString(),
            System.currentTimeMillis().toString(),
            queueProperties.maxCapacity.toString(),
            queueProperties.activeUser.ttl.toString(),
            scheduleId.toString()
        )

        @Suppress("UNCHECKED_CAST")
        val result = stringRedisTemplate.execute(queueEnterScript, keys, *args) as List<*>

        val code = (result[0] as Long).toInt()

        return when (code) {
            0, 1 -> {
                // 0: 신규 진입, 1: 동일 회차 중복 (멱등성)
                val zeroBasedRank = result[1] as Long
                val rank = zeroBasedRank + 1 // 0-based → 1-based 변환
                QueueDto.EnterResponse(
                    status = QueueStatus.WAITING,
                    scheduleId = scheduleId,
                    rank = rank,
                    estimatedWaitTime = calculateWaitTime(rank),
                    token = null
                )
            }
            2 -> throw QueueException(ErrorCode.ALREADY_IN_QUEUE)
            3 -> throw QueueException(ErrorCode.QUEUE_FULL)
            else -> throw QueueException(ErrorCode.INTERNAL_SERVER_ERROR)
        }
    }

    /**
     * 예상 대기 시간 계산 (초)
     *
     * 배치 처리 단위: batchSize명 / interval ms마다 승인
     * 공식: ceil(rank / batchSize) * (interval / 1000)
     */
    private fun calculateWaitTime(rank: Long): Long {
        val batchSize = queueProperties.batch.size.toLong()
        val intervalSeconds = queueProperties.batch.interval / 1000L
        return ((rank + batchSize - 1) / batchSize) * intervalSeconds
    }
}
