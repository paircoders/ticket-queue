package com.ticketqueue.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.event.BaseEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * `BaseEvent` 를 `OutboxEvent` 로 변환하여 Transactional Outbox 테이블에 저장하는 공용 헬퍼.
 *
 * **ID 바인딩 (SOT):** `OutboxEvent.id == event.eventId`
 *   → Consumer 의 `processed_events.event_id` 와 1:1 매핑 가능 (멱등성 추적 기반).
 *
 * **트랜잭션:** `PROPAGATION_MANDATORY` — 활성 트랜잭션 부재 시 즉시 예외.
 *   Transactional Outbox Pattern 은 비즈니스 변경과 outbox INSERT 의 원자성에 의존하므로,
 *   호출자(`@Transactional` 메서드 또는 `TransactionTemplate` 람다 내부)가 트랜잭션을 책임진다.
 *
 * **멱등성:** 동일 `eventId` 로 두 번 `record()` 호출 시 PK 충돌
 *   → `DataIntegrityViolationException`. 호출자가 재시도 멱등성 보장에 활용 가능.
 *
 * 재사용 예정: `ReservationService.cancelReservation()`, `PaymentService` (Outbox 도입 시).
 */
@Component
class OutboxEventRecorder(
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(event: BaseEvent): OutboxEvent =
        outboxEventRepository.save(
            OutboxEvent(
                id = event.eventId,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event)
            )
        )
}
