package com.ticketqueue.event.consumer

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.event.ReservationConfirmedEvent
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
import org.springframework.kafka.support.Acknowledgment
import java.util.UUID

class ReservationEventConsumerTest {

    private lateinit var idempotentConsumerTemplate: IdempotentConsumerTemplate
    private lateinit var seatService: SeatService
    private lateinit var consumer: ReservationEventConsumer

    private val objectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
    }

    private val scheduleId = UUID.randomUUID()
    private val seatIds = listOf(UUID.randomUUID(), UUID.randomUUID())
    private val ack = mockk<Acknowledgment>(relaxed = true)

    @BeforeEach
    fun setUp() {
        idempotentConsumerTemplate = mockk()
        seatService = mockk(relaxed = true)
        consumer = ReservationEventConsumer(idempotentConsumerTemplate, seatService, objectMapper)
    }

    private fun record(json: String) =
        ConsumerRecord<String, String>("reservation.events", 0, 0L, null, json)

    @Nested
    @DisplayName("ReservationConfirmed")
    inner class ReservationConfirmed {

        @Test
        @DisplayName("ReservationConfirmedEvent 수신 시 markSeatsAsSold를 위임한다")
        fun delegatesToMarkSeatsAsSold() {
            val event = ReservationConfirmedEvent(
                aggregateId = UUID.randomUUID(),
                scheduleId = scheduleId,
                seatIds = seatIds,
                userId = UUID.randomUUID()
            )
            val json = objectMapper.writeValueAsString(event)

            every {
                idempotentConsumerTemplate.process(any<ReservationConfirmedEvent>(), any(), any(), any())
            } answers {
                val businessLogic = arg<(ReservationConfirmedEvent) -> Unit>(3)
                businessLogic(firstArg())
            }

            consumer.consume(record(json), ack)

            verify { seatService.markSeatsAsSold(scheduleId, seatIds) }
        }

        @Test
        @DisplayName("멱등성 템플릿에 consumerService=event-service로 위임한다")
        fun delegatesWithCorrectConsumerService() {
            val event = ReservationConfirmedEvent(
                aggregateId = UUID.randomUUID(),
                scheduleId = scheduleId,
                seatIds = seatIds,
                userId = UUID.randomUUID()
            )
            val json = objectMapper.writeValueAsString(event)

            every {
                idempotentConsumerTemplate.process(any<ReservationConfirmedEvent>(), any(), any(), any())
            } just runs

            consumer.consume(record(json), ack)

            verify {
                idempotentConsumerTemplate.process(
                    any<ReservationConfirmedEvent>(),
                    eq("event-service"),
                    eq(ack),
                    any()
                )
            }
        }
    }

    @Nested
    @DisplayName("ReservationCancelled")
    inner class ReservationCancelled {

        @Test
        @DisplayName("ReservationCancelledEvent 수신 시 releaseHoldSeats를 위임한다")
        fun delegatesToReleaseHoldSeats() {
            val event = ReservationCancelledEvent(
                aggregateId = UUID.randomUUID(),
                scheduleId = scheduleId,
                seatIds = seatIds,
                userId = UUID.randomUUID(),
                reason = "timeout"
            )
            val json = objectMapper.writeValueAsString(event)

            every {
                idempotentConsumerTemplate.process(any<ReservationCancelledEvent>(), any(), any(), any())
            } answers {
                val businessLogic = arg<(ReservationCancelledEvent) -> Unit>(3)
                businessLogic(firstArg())
            }

            consumer.consume(record(json), ack)

            verify { seatService.releaseHoldSeats(scheduleId, seatIds) }
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
}
