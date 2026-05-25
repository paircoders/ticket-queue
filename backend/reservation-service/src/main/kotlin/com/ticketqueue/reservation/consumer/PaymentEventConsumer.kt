package com.ticketqueue.reservation.consumer

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.kafka.IdempotentConsumerTemplate
import com.ticketqueue.reservation.service.ReservationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * payment.events 토픽 Consumer (REQ-RSV-004)
 *
 * Reservation Service 측 SAGA 패턴 처리:
 * - PaymentSuccess → 예매 상태 CONFIRMED + 티켓 번호 발급 ([ReservationService.confirmFromPaymentSuccess])
 * - PaymentFailed  → 예매 상태 CANCELLED + ReservationCancelled outbox 발행
 *                    ([ReservationService.cancelFromPaymentFailure])
 *
 * Consumer Group: reservation-payment-consumer (application.yml 에 정의)
 *
 * ## 동일 토픽의 다중 이벤트 타입 핸들링
 * payment.events 에는 PaymentSuccess / PaymentFailed 두 종류가 흐른다.
 * application.yml 기본 deserializer 는 JsonDeserializer 이지만, listener 단위로
 * StringDeserializer override 후 raw JSON 에서 `eventType` 을 먼저 파싱해 분기한다.
 * 이렇게 하면 Producer / Consumer 가 동일한 Wire 포맷(전체 JSON)을 공유하면서도
 * Consumer 가 타입별로 안전하게 typed 변환을 할 수 있다.
 *
 * ## DLQ 처리 전략 (REQ-RSV-020)
 * @RetryableTopic 대신 KafkaErrorHandlerConfig 전역 DefaultErrorHandler 를 사용한다.
 * - 지수 백오프: 1초 → 2초 → 4초 (최대 10초), 최대 3회 재시도
 * - Non-retryable 예외(DataIntegrityViolationException, JsonProcessingException 등) → 즉시 dlq.payment 이동
 * - Retryable 예외(TimeoutException, KafkaException 등) → 3회 재시도 후 dlq.payment 이동
 */
@Component
class PaymentEventConsumer(
    private val idempotentConsumerTemplate: IdempotentConsumerTemplate,
    private val reservationService: ReservationService,
    private val objectMapper: ObjectMapper,
) {

    private val log = KotlinLogging.logger {}

    @KafkaListener(
        topics = ["payment.events"],
        groupId = "reservation-payment-consumer",
        properties = ["value.deserializer=org.apache.kafka.common.serialization.StringDeserializer"]
    )
    fun consume(record: ConsumerRecord<String, String>, ack: Acknowledgment) {
        val rawJson = record.value()
        val eventType = try {
            objectMapper.readTree(rawJson).get("eventType")?.asText()
        } catch (e: JsonProcessingException) {
            log.error(e) { "Malformed JSON in payment.events, sending to DLQ: $rawJson" }
            throw e
        }

        when (eventType) {
            "PaymentSuccess" -> {
                val event = try {
                    objectMapper.readValue(rawJson, PaymentSuccessEvent::class.java)
                } catch (e: JsonProcessingException) {
                    log.error(e) { "Malformed PaymentSuccess JSON, sending to DLQ: $rawJson" }
                    throw e
                }
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    reservationService.confirmFromPaymentSuccess(e)
                }
            }
            "PaymentFailed" -> {
                val event = try {
                    objectMapper.readValue(rawJson, PaymentFailedEvent::class.java)
                } catch (e: JsonProcessingException) {
                    log.error(e) { "Malformed PaymentFailed JSON, sending to DLQ: $rawJson" }
                    throw e
                }
                idempotentConsumerTemplate.process(event, CONSUMER_SERVICE, ack) { e ->
                    reservationService.cancelFromPaymentFailure(e)
                }
            }
            else -> {
                log.warn { "Unknown payment event type=$eventType in reservation consumer, skipping" }
                ack.acknowledge()
            }
        }
    }

    companion object {
        private const val CONSUMER_SERVICE = "reservation-service"
    }
}
