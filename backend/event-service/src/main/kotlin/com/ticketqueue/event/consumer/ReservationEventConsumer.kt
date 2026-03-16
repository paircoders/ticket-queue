package com.ticketqueue.event.consumer

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.event.ReservationConfirmedEvent
import com.ticketqueue.common.kafka.IdempotentConsumerTemplate
import com.ticketqueue.event.service.SeatService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * reservation.events 토픽 Consumer
 *
 * SAGA 패턴에서 예매 상태 변경에 따른 좌석 상태를 동기화한다.
 * - ReservationConfirmed → no-op (SOLD 처리는 PaymentSuccess 이벤트에서 수행)
 * - ReservationCancelled → DB AVAILABLE 복원 + Redis hold_seats 선점 해제 (releaseHoldSeats)
 *
 * IdempotentConsumerTemplate으로 중복 이벤트를 방지한다.
 * Consumer Group: event-reservation-consumer
 */
@Component
class ReservationEventConsumer(
    private val idempotentConsumerTemplate: IdempotentConsumerTemplate,
    private val seatService: SeatService,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(ReservationEventConsumer::class.java)

    @KafkaListener(
        topics = ["reservation.events"],
        groupId = "event-reservation-consumer",
        properties = ["value.deserializer=org.apache.kafka.common.serialization.StringDeserializer"]
    )
    fun consume(record: ConsumerRecord<String, String>, ack: Acknowledgment) {
        val rawJson = record.value()
        val eventType = try {
            objectMapper.readTree(rawJson).get("eventType")?.asText()
        } catch (e: JsonProcessingException) {
            log.error("Malformed JSON in reservation.events, sending to DLQ: {}", rawJson, e)
            throw e
        }

        when (eventType) {
            "ReservationConfirmed" -> {
                val event = try {
                    objectMapper.readValue(rawJson, ReservationConfirmedEvent::class.java)
                } catch (e: JsonProcessingException) {
                    log.error("Malformed JSON in reservation.events, sending to DLQ: {}", rawJson, e)
                    throw e
                }
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    log.info("ReservationConfirmed: reservationId=${e.aggregateId}, scheduleId=${e.scheduleId} (SOLD 처리는 PaymentSuccess에서 수행)")
                }
            }
            "ReservationCancelled" -> {
                val event = try {
                    objectMapper.readValue(rawJson, ReservationCancelledEvent::class.java)
                } catch (e: JsonProcessingException) {
                    log.error("Malformed JSON in reservation.events, sending to DLQ: {}", rawJson, e)
                    throw e
                }
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    seatService.releaseHoldSeats(e.scheduleId, e.seatIds)
                }
            }
            else -> {
                log.warn("Unknown reservation event type: $eventType, skipping")
                ack.acknowledge()
            }
        }
    }

    companion object {
        private const val CONSUMER_SERVICE = "event-service"
    }
}
