package com.ticketqueue.common.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate

/**
 * Outbox Poller 설정 등록
 *
 * Kafka 의존성이 있는 서비스(Producer: Reservation, Payment)에서만 활성화됩니다.
 * OutboxPollerProperties를 common-kafka 레이어에서 등록하여 common-jpa와의 결합을 분리합니다.
 */
@Configuration
@ConditionalOnClass(KafkaTemplate::class)
@EnableConfigurationProperties(OutboxPollerProperties::class)
class OutboxKafkaConfig
