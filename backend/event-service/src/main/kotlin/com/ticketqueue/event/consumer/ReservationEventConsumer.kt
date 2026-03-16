package com.ticketqueue.event.consumer

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
 *
 * ## DLQ 처리 전략 (REQ-EVT-020)
 *
 * @RetryableTopic 대신 KafkaErrorHandlerConfig의 전역 DefaultErrorHandler를 사용한다.
 * 이유:
 * 1. 수동 ack 모드(MANUAL_IMMEDIATE)와 @RetryableTopic은 호환되지 않음
 * 2. @RetryableTopic 기본 DLT suffix(-dlt)가 기존 DLQ 토픽명(dlq.reservation)과 불일치
 * 3. DefaultErrorHandler가 이미 동일한 재시도 정책을 적용 중
 *    - 지수 백오프: 1초 → 2초 → 4초 (최대 10초), 최대 3회 재시도
 *    - Non-retryable 예외(DataIntegrityViolationException, JsonProcessingException 등) → 즉시 dlq.reservation 이동
 *    - Retryable 예외(TimeoutException, KafkaException 등) → 3회 재시도 후 dlq.reservation 이동
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
        val eventType = objectMapper.readTree(rawJson).get("eventType")?.asText()

        when (eventType) {
            "ReservationConfirmed" -> {
                val event = objectMapper.readValue(rawJson, ReservationConfirmedEvent::class.java)
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    log.info("ReservationConfirmed: reservationId=${e.aggregateId}, scheduleId=${e.scheduleId} (SOLD 처리는 PaymentSuccess에서 수행)")
                }
            }
            "ReservationCancelled" -> {
                val event = objectMapper.readValue(rawJson, ReservationCancelledEvent::class.java)
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
