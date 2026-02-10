package com.ticketqueue.common.outbox

import com.ticketqueue.common.kafka.KafkaTopicConfig
import org.springframework.stereotype.Component

/**
 * Kafka 토픽 매핑 리졸버
 *
 * **핵심 역할:**
 * - Aggregate Type(도메인 객체명) → Kafka 토픽 변환
 * - 정상 토픽 → DLQ 토픽 변환
 *
 * **매핑 규칙:**
 * - Payment → payment.events (Payment Service Producer)
 * - Reservation → reservation.events (Reservation Service Producer)
 * - 그 외 Service는 Producer가 아니므로 매핑 없음 (Consumer 전용)
 *
 * **DLQ 토픽 네이밍 규칙:**
 * - 규칙: "dlq.{서비스명}"
 * - payment.events → dlq.payment
 * - reservation.events → dlq.reservation
 *
 * **구현 위임:**
 * - 실제 매핑 로직은 KafkaTopicConfig에 위임
 * - OutboxTopicResolver는 Outbox 서비스용 인터페이스 제공
 *
 * @see OutboxPollerService
 * @see KafkaTopicConfig
 */
@Component
class OutboxTopicResolver {

    /**
     * Aggregate Type으로 Kafka 토픽 조회
     *
     * @param aggregateType 도메인 객체명 (예: "Payment", "Reservation")
     * @return Kafka 토픽명 (예: "payment.events")
     * @throws IllegalArgumentException 매핑 테이블에 없는 aggregateType인 경우
     */
    fun resolveTopic(aggregateType: String): String {
        return KafkaTopicConfig.findByAggregateType(aggregateType)?.topic
            ?: throw IllegalArgumentException("Unknown aggregate type: $aggregateType")
    }

    /**
     * 정상 토픽으로 DLQ 토픽 조회
     *
     * **Fallback 동작:**
     * - 매핑 테이블에 없으면 "dlq.{토픽명}" 자동 생성
     * - 예: resolveDlqTopic("unknown.topic") → "dlq.unknown.topic"
     *
     * @param topic 원본 Kafka 토픽명 (예: "payment.events")
     * @return DLQ 토픽명 (예: "dlq.payment")
     */
    fun resolveDlqTopic(topic: String): String {
        return KafkaTopicConfig.resolveDlqTopic(topic)
    }
}
