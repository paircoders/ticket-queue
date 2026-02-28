package com.ticketqueue.event.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.kafka.IdempotentConsumerTemplate
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * payment.events 토픽 Consumer
 *
 * Issue #39 체크리스트 충족을 위해 구독하되, 좌석 상태 변경은 수행하지 않는다.
 * (SOLD 처리는 ReservationConfirmed 이벤트 기반으로 수행)
 *
 * 멱등성 기록 + 로깅만 수행한다.
 * Consumer Group: event-payment-consumer
 */
@Component
class PaymentEventConsumer(
    private val idempotentConsumerTemplate: IdempotentConsumerTemplate,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(PaymentEventConsumer::class.java)

    @KafkaListener(
        topics = ["payment.events"],
        groupId = "event-payment-consumer",
        properties = ["value.deserializer=org.apache.kafka.common.serialization.StringDeserializer"]
    )
    fun consume(record: ConsumerRecord<String, String>, ack: Acknowledgment) {
        val rawJson = record.value()
        val eventType = objectMapper.readTree(rawJson).get("eventType")?.asText()

        when (eventType) {
            "PaymentSuccess" -> {
                val event = objectMapper.readValue(rawJson, PaymentSuccessEvent::class.java)
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    log.info("PaymentSuccess: paymentId=${e.aggregateId}, reservationId=${e.reservationId}")
                }
            }
            "PaymentFailed" -> {
                val event = objectMapper.readValue(rawJson, PaymentFailedEvent::class.java)
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    log.info("PaymentFailed: paymentId=${e.aggregateId}, reservationId=${e.reservationId}, reason=${e.reason}")
                }
            }
            else -> {
                log.warn("Unknown payment event type: $eventType, skipping")
                ack.acknowledge()
            }
        }
    }

    companion object {
        private const val CONSUMER_SERVICE = "event-service"
    }
}
