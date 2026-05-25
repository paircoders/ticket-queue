package com.ticketqueue.event.consumer

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.kafka.IdempotentConsumerTemplate
import com.ticketqueue.event.service.SeatService
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

class PaymentEventConsumerTest {

    private lateinit var idempotentConsumerTemplate: IdempotentConsumerTemplate
    private lateinit var seatService: SeatService
    private lateinit var consumer: PaymentEventConsumer

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val ack = mockk<Acknowledgment>(relaxed = true)

    @BeforeEach
    fun setUp() {
        idempotentConsumerTemplate = mockk()
        seatService = mockk(relaxed = true)
        consumer = PaymentEventConsumer(idempotentConsumerTemplate, seatService, objectMapper)
    }

    private fun record(json: String) =
        ConsumerRecord<String, String>("payment.events", 0, 0L, null, json)

    @Nested
    @DisplayName("PaymentSuccess")
    inner class PaymentSuccess {

        @Test
        @DisplayName("PaymentSuccessEvent 수신 시 SOLD 처리 및 멱등성 처리를 수행한다")
        fun processesWithIdempotency() {
            val scheduleId = UUID.randomUUID()
            val seatIds = listOf(UUID.randomUUID(), UUID.randomUUID())
            val event = PaymentSuccessEvent(
                aggregateId = UUID.randomUUID(),
                reservationId = UUID.randomUUID(),
                paymentKey = "pay_key_123",
                amount = BigDecimal("100000"),
                paidAt = LocalDateTime.now(),
                scheduleId = scheduleId,
                seatIds = seatIds,
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
                    eq("event-service"),
                    eq(ack),
                    any()
                )
            }
            verify { seatService.markSeatsAsSold(scheduleId, seatIds) }
        }
    }

    @Nested
    @DisplayName("PaymentFailed")
    inner class PaymentFailed {

        @Test
        @DisplayName("PaymentFailedEvent 수신 시 멱등성 처리를 수행하고 SOLD 처리는 하지 않는다")
        fun processesWithIdempotency() {
            val event = PaymentFailedEvent(
                aggregateId = UUID.randomUUID(),
                reservationId = UUID.randomUUID(),
                reason = "insufficient funds"
            )
            val json = objectMapper.writeValueAsString(event)

            every {
                idempotentConsumerTemplate.process(any<PaymentFailedEvent>(), any(), any(), any())
            } just runs

            consumer.consume(record(json), ack)

            verify {
                idempotentConsumerTemplate.process(
                    any<PaymentFailedEvent>(),
                    eq("event-service"),
                    eq(ack),
                    any()
                )
            }
            verify(exactly = 0) { seatService.markSeatsAsSold(any(), any()) }
        }
    }

    @Nested
    @DisplayName("Unknown eventType")
    inner class UnknownEventType {

        @Test
        @DisplayName("알 수 없는 eventType 수신 시 ack만 수행하고 템플릿을 호출하지 않는다")
        fun acknowledgesAndSkipsUnknownEventType() {
            val json = """{"eventType":"UnknownEvent","eventId":"${UUID.randomUUID()}"}"""

            consumer.consume(record(json), ack)

            verify { ack.acknowledge() }
            verify(exactly = 0) { idempotentConsumerTemplate.process(any(), any(), any(), any()) }
        }
    }

    @Nested
    @DisplayName("Malformed JSON (Poison Pill)")
    inner class MalformedJson {

        @Test
        @DisplayName("malformed JSON 수신 시 JsonProcessingException을 던져 DLQ로 이동한다")
        fun throwsJsonProcessingExceptionOnMalformedJson() {
            val malformedJson = "{broken json"

            assertThrows<JsonProcessingException> {
                consumer.consume(record(malformedJson), ack)
            }
            verify(exactly = 0) { ack.acknowledge() }
        }
    }
}
