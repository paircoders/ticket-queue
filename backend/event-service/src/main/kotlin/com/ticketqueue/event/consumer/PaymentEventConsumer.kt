package com.ticketqueue.event.consumer

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.kafka.IdempotentConsumerTemplate
import com.ticketqueue.event.service.SeatService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * payment.events 토픽 Consumer
 *
 * - PaymentSuccess → 좌석 SOLD 처리 (markSeatsAsSold)
 * - PaymentFailed  → 멱등성 기록 + 로깅 (보상 트랜잭션은 ReservationCancelled 흐름에서 처리)
 *
 * Consumer Group: event-payment-consumer
 */
@Component
class PaymentEventConsumer(
    private val idempotentConsumerTemplate: IdempotentConsumerTemplate,
    private val seatService: SeatService,
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
        val eventType = try {
            objectMapper.readTree(rawJson).get("eventType")?.asText()
        } catch (e: JsonProcessingException) {
            log.error("Malformed JSON in payment.events, sending to DLQ: {}", rawJson, e)
            throw e
        }

        when (eventType) {
            "PaymentSuccess" -> {
                val event = try {
                    objectMapper.readValue(rawJson, PaymentSuccessEvent::class.java)
                } catch (e: JsonProcessingException) {
                    log.error("Malformed JSON in payment.events, sending to DLQ: {}", rawJson, e)
                    throw e
                }
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    seatService.markSeatsAsSold(e.scheduleId, e.seatIds)
                    log.info("PaymentSuccess: paymentId=${e.aggregateId}, reservationId=${e.reservationId}")
                }
            }
            "PaymentFailed" -> {
                val event = try {
                    objectMapper.readValue(rawJson, PaymentFailedEvent::class.java)
                } catch (e: JsonProcessingException) {
                    log.error("Malformed JSON in payment.events, sending to DLQ: {}", rawJson, e)
                    throw e
                }
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
