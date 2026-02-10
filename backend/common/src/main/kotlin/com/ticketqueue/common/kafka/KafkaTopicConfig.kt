package com.ticketqueue.common.kafka

/**
 * Kafka 토픽 매핑 중앙 관리 (Single Source of Truth)
 *
 * **중앙화 근거:**
 * - 기존: OutboxTopicResolver와 KafkaErrorHandlerConfig에 동일 매핑 중복 정의
 * - 개선: 이 Enum이 유일한 매핑 원천 → 서비스 추가 시 한 곳만 수정
 *
 * **사용처:**
 * - OutboxTopicResolver: Outbox Poller가 이벤트 발행 시 토픽 결정
 * - KafkaErrorHandlerConfig: Consumer 에러 핸들러가 DLQ 토픽 결정
 *
 * **확장 방법:**
 * - 새 서비스 추가 시 Enum 항목 1개만 추가
 *   예: NOTIFICATION("Notification", "notification.events", "dlq.notification")
 *
 * @property aggregateType Outbox 이벤트의 aggregate_type 필드 값
 * @property topic 메인 Kafka 토픽명
 * @property dlqTopic Dead Letter Queue 토픽명
 */
enum class KafkaTopicConfig(
    val aggregateType: String,
    val topic: String,
    val dlqTopic: String
) {
    PAYMENT("Payment", "payment.events", "dlq.payment"),
    RESERVATION("Reservation", "reservation.events", "dlq.reservation");

    companion object {
        /**
         * aggregateType 기반 빠른 조회용 Map
         */
        private val byAggregateType: Map<String, KafkaTopicConfig> =
            entries.associateBy { it.aggregateType }

        /**
         * topic 기반 빠른 조회용 Map
         */
        private val byTopic: Map<String, KafkaTopicConfig> =
            entries.associateBy { it.topic }

        /**
         * aggregateType으로 토픽 설정 조회
         *
         * @param aggregateType Outbox 이벤트의 aggregate_type 값
         * @return 매칭되는 KafkaTopicConfig 또는 null
         */
        fun findByAggregateType(aggregateType: String): KafkaTopicConfig? =
            byAggregateType[aggregateType]

        /**
         * 토픽명으로 DLQ 토픽명 결정
         *
         * @param topic 메인 Kafka 토픽명
         * @return 매핑된 DLQ 토픽명, 또는 기본 규칙("dlq.$topic")
         */
        fun resolveDlqTopic(topic: String): String =
            byTopic[topic]?.dlqTopic ?: "dlq.$topic"
    }
}
