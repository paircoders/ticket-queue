package com.ticketqueue.common.outbox

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Outbox 이벤트 조회 전용 서비스
 *
 * **분리 이유:**
 * - Spring AOP 프록시 기반 트랜잭션 처리를 위해 별도 Bean으로 분리
 * - OutboxPollerService 내부에서 @Transactional 메서드 호출 시 self-invocation 문제 발생
 * - 별도 Bean으로 분리하여 프록시를 통한 트랜잭션 관리 보장
 *
 * **동시성 제어 (FOR UPDATE SKIP LOCKED):**
 * - 다중 인스턴스 환경에서 동일 이벤트 중복 조회 방지
 * - PESSIMISTIC_WRITE Lock: DB 레벨에서 행 잠금
 * - SKIP LOCKED: 이미 다른 인스턴스가 잠근 행은 건너뜀
 * - 효과: 각 인스턴스가 서로 다른 이벤트를 조회하여 경합 없이 병렬 처리
 */
@Service
class OutboxPollerQueryService(
    private val outboxEventRepository: OutboxEventRepository
) {
    /**
     * 미발행 이벤트 조회 (트랜잭션 내에서 PESSIMISTIC LOCK 획득)
     *
     * **트랜잭션 분리 이유:**
     * - 조회 시 PESSIMISTIC LOCK 획득 (readOnly = false 필수)
     * - 발행은 각 이벤트별로 독립적인 WRITE 트랜잭션 (processEvent)
     * - 한 이벤트 발행 실패가 다른 이벤트에 영향주지 않음
     *
     * **readOnly = false인 이유:**
     * - FOR UPDATE 쿼리는 READ-ONLY 트랜잭션에서 실행 불가
     * - PESSIMISTIC LOCK 획득 자체가 WRITE 작업
     * - PostgreSQL 에러: "cannot execute SELECT FOR NO KEY UPDATE in a read-only transaction"
     */
    @Transactional(readOnly = false)
    fun fetchUnpublishedEvents(maxRetryCount: Int, batchSize: Int): List<OutboxEvent> {
        return outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
            maxRetryCount,
            PageRequest.of(0, batchSize)
        )
    }
}
