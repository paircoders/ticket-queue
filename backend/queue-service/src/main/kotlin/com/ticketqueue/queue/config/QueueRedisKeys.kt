package com.ticketqueue.queue.config

/**
 * Queue 서비스에서 사용하는 Redis 키 패턴 중앙 관리 객체.
 *
 * Redis 데이터 모델 (CLAUDE.md 참고):
 * - queue:{scheduleId}                     — 대기열 Sorted Set (회차별)
 * - queue:user-token:{userId}:{scheduleId} — 역방향 토큰 조회 키 (배치 승인 시 SET)
 *   ⚠️ batch-approve 로직 구현 시 반드시 이 키를 SET해야 함 (Issue #44)
 *   queue-status.lua가 이 키를 GET하여 ACTIVE 상태 판단
 * - queue:active:{userId}                  — 중복 대기 방지 (scheduleId 저장)
 * - rate:queue-status:{userId}             — 상태 조회 Rate Limit 카운터
 */
object QueueRedisKeys {
    fun queue(scheduleId: Any): String = "queue:$scheduleId"
    fun userToken(userId: Any, scheduleId: Any): String = "queue:user-token:$userId:$scheduleId"
    fun active(userId: Any): String = "queue:active:$userId"
    fun rateLimit(userId: Any): String = "rate:queue-status:$userId"
    fun token(token: Any): String = "queue:token:$token"

    /**
     * 활성 대기열 scheduleId 추적 SET 키 (REQ-QUEUE-005)
     *
     * SCAN/KEYS 명령 대신 이 SET을 사용하여 배치 승인 대상 회차를 O(1)으로 조회한다.
     * - 대기열 진입 시: SADD (queue-enter.lua)
     * - 대기열 비워질 때: SREM (batch-approve.lua)
     */
    fun activeSchedules(): String = "queue:active-schedules"
}
