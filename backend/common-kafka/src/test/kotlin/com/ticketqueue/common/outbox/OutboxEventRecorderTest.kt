package com.ticketqueue.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.event.EventMetadata
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.event.ReservationConfirmedEvent
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

@DisplayName("OutboxEventRecorder 단위 테스트")
class OutboxEventRecorderTest {

    private lateinit var outboxEventRepository: OutboxEventRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var recorder: OutboxEventRecorder

    @BeforeEach
    fun setUp() {
        outboxEventRepository = mockk()
        objectMapper = jacksonObjectMapper().apply { registerModule(JavaTimeModule()) }
        recorder = OutboxEventRecorder(outboxEventRepository, objectMapper)
    }

    @Test
    @DisplayName("record() 호출 시 OutboxEvent.id == event.eventId (SOT 바인딩)")
    fun recordBindsEventIdToOutboxId() {
        val event = sampleCancelledEvent()
        val slot = slot<OutboxEvent>()
        every { outboxEventRepository.save(capture(slot)) } answers { firstArg() }

        recorder.record(event)

        slot.captured.id shouldBe event.eventId
    }

    @Test
    @DisplayName("aggregateType, aggregateId, eventType이 BaseEvent에서 정확히 전달된다")
    fun recordMapsBaseEventFields() {
        val event = sampleCancelledEvent()
        val slot = slot<OutboxEvent>()
        every { outboxEventRepository.save(capture(slot)) } answers { firstArg() }

        recorder.record(event)

        slot.captured.aggregateType shouldBe "Reservation"
        slot.captured.eventType shouldBe "ReservationCancelled"
        slot.captured.aggregateId shouldBe event.aggregateId
    }

    @Test
    @DisplayName("payload는 ObjectMapper로 직렬화된 JSON 문자열이며 원본 이벤트와 동등하게 역직렬화된다")
    fun recordSerializesPayloadAsRoundtrippableJson() {
        val event = sampleCancelledEvent()
        val slot = slot<OutboxEvent>()
        every { outboxEventRepository.save(capture(slot)) } answers { firstArg() }

        recorder.record(event)

        val deserialized = objectMapper.readValue(slot.captured.payload, ReservationCancelledEvent::class.java)
        deserialized.eventId shouldBe event.eventId
        deserialized.aggregateId shouldBe event.aggregateId
        deserialized.scheduleId shouldBe event.scheduleId
        deserialized.seatIds shouldBe event.seatIds
        deserialized.userId shouldBe event.userId
        deserialized.reason shouldBe event.reason
    }

    @Test
    @DisplayName("다른 BaseEvent 서브타입(ReservationConfirmedEvent)에서도 동일하게 동작한다")
    fun recordWorksForOtherBaseEventSubtypes() {
        val event = ReservationConfirmedEvent(
            aggregateId = UUID.randomUUID(),
            scheduleId = UUID.randomUUID(),
            seatIds = listOf(UUID.randomUUID()),
            userId = UUID.randomUUID()
        )
        val slot = slot<OutboxEvent>()
        every { outboxEventRepository.save(capture(slot)) } answers { firstArg() }

        recorder.record(event)

        slot.captured.id shouldBe event.eventId
        slot.captured.aggregateType shouldBe "Reservation"
        slot.captured.eventType shouldBe "ReservationConfirmed"
    }

    @Test
    @DisplayName("repository.save()가 DataIntegrityViolationException을 던지면 record()가 동일 예외를 전파한다")
    fun recordPropagatesPkConflict() {
        val event = sampleCancelledEvent()
        every { outboxEventRepository.save(any()) } throws
            DataIntegrityViolationException("duplicate key value violates unique constraint")

        assertThrows<DataIntegrityViolationException> {
            recorder.record(event)
        }
    }

    private fun sampleCancelledEvent() = ReservationCancelledEvent(
        aggregateId = UUID.randomUUID(),
        scheduleId = UUID.randomUUID(),
        seatIds = listOf(UUID.randomUUID(), UUID.randomUUID()),
        userId = UUID.randomUUID(),
        reason = "USER_REQUEST",
        metadata = EventMetadata(
            correlationId = UUID.randomUUID(),
            causationId = null,
            userId = UUID.randomUUID()
        )
    )
}
