package com.ticketqueue.common.outbox

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
 * @see OutboxPollerService
 */
@Component
class OutboxTopicResolver {

    companion object {
        /**
         * Aggregate Type → Kafka 토픽 매핑
         *
         * **주의:**
         * - aggregateType은 OutboxEvent 저장 시 비즈니스 서비스가 지정 ("Payment", "Reservation")
         * - 대소문자 정확히 일치해야 함 (대소문자 오타 시 IllegalArgumentException)
         */
        private val AGGREGATE_TO_TOPIC = mapOf(
            "Payment" to "payment.events",
            "Reservation" to "reservation.events"
        )

        /**
         * 정상 토픽 → DLQ 토픽 매핑
         *
         * **Fallback 전략:**
         * - 매핑 테이블에 없으면 "dlq.{토픽명}" 자동 생성
         * - 예: "new.topic" → "dlq.new.topic"
         * - 이유: 새 서비스 추가 시 DLQ 매핑 누락해도 DLQ 이동 실패 방지
         */
        private val TOPIC_TO_DLQ = mapOf(
            "payment.events" to "dlq.payment",
            "reservation.events" to "dlq.reservation"
        )
    }

    /**
     * Aggregate Type으로 Kafka 토픽 조회
     *
     * @param aggregateType 도메인 객체명 (예: "Payment", "Reservation")
     * @return Kafka 토픽명 (예: "payment.events")
     * @throws IllegalArgumentException 매핑 테이블에 없는 aggregateType인 경우
     */
    fun resolveTopic(aggregateType: String): String {
        return AGGREGATE_TO_TOPIC[aggregateType]
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
        return TOPIC_TO_DLQ[topic] ?: "dlq.$topic"
    }
}
