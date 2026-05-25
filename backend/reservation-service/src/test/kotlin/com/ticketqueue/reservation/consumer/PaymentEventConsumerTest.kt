package com.ticketqueue.reservation.consumer

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.kafka.IdempotentConsumerTemplate
import com.ticketqueue.reservation.service.ReservationService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.support.Acknowledgment
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Reservation Service 의 payment.events 토픽 Consumer 단위 테스트.
 *
 * 검증 범위:
 * - eventType 분기 라우팅 (PaymentSuccess / PaymentFailed / Unknown)
 * - IdempotentConsumerTemplate 위임 시 consumerService 식별자 ("reservation-service")
 * - ReservationService 비즈니스 메서드 호출 여부
 * - malformed JSON 시 DLQ 이동을 위한 JsonProcessingException 전파
 *
 * 멱등성/트랜잭션 흐름은 ReservationServiceTest 와 IdempotentConsumerTemplateTest 가 별도 커버한다.
 */
@DisplayName("PaymentEventConsumer (reservation-service)")
class PaymentEventConsumerTest {

    private lateinit var idempotentConsumerTemplate: IdempotentConsumerTemplate
    private lateinit var reservationService: ReservationService
    private lateinit var consumer: PaymentEventConsumer

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val ack = mockk<Acknowledgment>(relaxed = true)

    @BeforeEach
    fun setUp() {
        idempotentConsumerTemplate = mockk()
        reservationService = mockk(relaxed = true)
        consumer = PaymentEventConsumer(idempotentConsumerTemplate, reservationService, objectMapper)
    }

    private fun record(json: String) =
        ConsumerRecord<String, String>("payment.events", 0, 0L, null, json)

    @Nested
    @DisplayName("PaymentSuccess")
    inner class PaymentSuccess {

        @Test
        @DisplayName("PaymentSuccessEvent 수신 시 ReservationService.confirmFromPaymentSuccess 를 호출한다")
        fun routesToConfirmFromPaymentSuccess() {
            val event = PaymentSuccessEvent(
                aggregateId = UUID.randomUUID(),
                reservationId = UUID.randomUUID(),
                paymentKey = "pay_key_123",
                amount = BigDecimal("100000"),
                paidAt = LocalDateTime.now(),
                scheduleId = UUID.randomUUID(),
                seatIds = listOf(UUID.randomUUID()),
                portoneTransactionId = "portone_tx_123"
            )
            val json = objectMapper.writeValueAsString(event)

            every {
                idempotentConsumerTemplate.process(any<PaymentSuccessEvent>(), any(), any(), any())
            } answers {
                val businessLogic = arg<(PaymentSuccessEvent) -> Unit>(3)
                businessLogic(firstArg())
            }

            consumer.consume(record(json), ack)

            verify {
                idempotentConsumerTemplate.process(
                    any<PaymentSuccessEvent>(),
                    eq("reservation-service"),
                    eq(ack),
                    any()
                )
            }
            verify { reservationService.confirmFromPaymentSuccess(any()) }
            verify(exactly = 0) { reservationService.cancelFromPaymentFailure(any()) }
        }
    }

    @Nested
    @DisplayName("PaymentFailed")
    inner class PaymentFailed {

        @Test
        @DisplayName("PaymentFailedEvent 수신 시 ReservationService.cancelFromPaymentFailure 를 호출한다")
        fun routesToCancelFromPaymentFailure() {
            val event = PaymentFailedEvent(
                aggregateId = UUID.randomUUID(),
                reservationId = UUID.randomUUID(),
                reason = "INSUFFICIENT_BALANCE"
            )
            val json = objectMapper.writeValueAsString(event)

            every {
                idempotentConsumerTemplate.process(any<PaymentFailedEvent>(), any(), any(), any())
            } answers {
                val businessLogic = arg<(PaymentFailedEvent) -> Unit>(3)
                businessLogic(firstArg())
            }

            consumer.consume(record(json), ack)

            verify {
                idempotentConsumerTemplate.process(
                    any<PaymentFailedEvent>(),
                    eq("reservation-service"),
                    eq(ack),
                    any()
                )
            }
            verify { reservationService.cancelFromPaymentFailure(any()) }
            verify(exactly = 0) { reservationService.confirmFromPaymentSuccess(any()) }
        }

        @Test
        @DisplayName("Template 호출만 받고 비즈니스 로직 실행이 지연되어도 confirmFromPaymentSuccess 는 호출되지 않는다")
        fun delegatesEvenWhenBusinessLogicNotInvoked() {
            val event = PaymentFailedEvent(
                aggregateId = UUID.randomUUID(),
                reservationId = UUID.randomUUID(),
                reason = "INSUFFICIENT_BALANCE"
            )
            val json = objectMapper.writeValueAsString(event)

            every {
                idempotentConsumerTemplate.process(any<PaymentFailedEvent>(), any(), any(), any())
            } just runs

            consumer.consume(record(json), ack)

            verify { idempotentConsumerTemplate.process(any<PaymentFailedEvent>(), eq("reservation-service"), eq(ack), any()) }
            verify(exactly = 0) { reservationService.confirmFromPaymentSuccess(any()) }
            verify(exactly = 0) { reservationService.cancelFromPaymentFailure(any()) }
        }
    }

    @Nested
    @DisplayName("Unknown eventType")
    inner class UnknownEventType {

        @Test
        @DisplayName("알 수 없는 eventType 수신 시 ack 만 수행하고 ReservationService / Template 모두 호출하지 않는다")
        fun acknowledgesAndSkips() {
            val json = """{"eventType":"UnknownEvent","eventId":"${UUID.randomUUID()}"}"""

            consumer.consume(record(json), ack)

            verify { ack.acknowledge() }
            verify(exactly = 0) { idempotentConsumerTemplate.process(any(), any(), any(), any()) }
            verify(exactly = 0) { reservationService.confirmFromPaymentSuccess(any()) }
            verify(exactly = 0) { reservationService.cancelFromPaymentFailure(any()) }
        }
    }

    @Nested
    @DisplayName("Malformed JSON (Poison Pill)")
    inner class MalformedJson {

        @Test
        @DisplayName("malformed JSON 수신 시 JsonProcessingException 을 던져 DLQ 로 이동한다 (ack 없음)")
        fun throwsAndSkipsAck() {
            val malformedJson = "{broken json"

            assertThrows<JsonProcessingException> {
                consumer.consume(record(malformedJson), ack)
            }
            verify(exactly = 0) { ack.acknowledge() }
        }
    }
}
