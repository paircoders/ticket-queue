package com.ticketqueue.common.kafka

import com.ticketqueue.common.event.BaseEvent
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant
import java.util.UUID

class IdempotentConsumerTemplateTest {

    private val processedEventService = mockk<ProcessedEventService>()
    private val acknowledgment = mockk<Acknowledgment>()
    private val template = IdempotentConsumerTemplate(processedEventService)

    private val testEventId = UUID.randomUUID()
    private val consumerService = "test-consumer"

    private val testEvent = object : BaseEvent(
        eventId = testEventId,
        eventType = "TEST_EVENT",
        aggregateId = UUID.randomUUID(),
        aggregateType = "TEST_AGGREGATE",
    ) {}

    @BeforeEach
    fun setUp() {
        justRun { acknowledgment.acknowledge() }
        justRun { processedEventService.deleteRecord(any(), any()) }
    }

    @Test
    fun `non-retryable 예외 발생 시 deleteRecord 호출 후 예외 전파`() {
        every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true

        assertThrows<IllegalArgumentException> {
            template.process(testEvent, consumerService, acknowledgment) {
                throw IllegalArgumentException("validation error")
            }
        }

        verify(exactly = 1) { processedEventService.deleteRecord(testEventId, consumerService) }
        verify(exactly = 0) { acknowledgment.acknowledge() }
    }

    @Test
    fun `retryable 예외 발생 시 deleteRecord 호출 후 예외 전파`() {
        every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true

        assertThrows<java.util.concurrent.TimeoutException> {
            template.process(testEvent, consumerService, acknowledgment) {
                throw java.util.concurrent.TimeoutException("timeout")
            }
        }

        verify(exactly = 1) { processedEventService.deleteRecord(testEventId, consumerService) }
        verify(exactly = 0) { acknowledgment.acknowledge() }
    }

    @Test
    fun `중복 이벤트 skip 시 deleteRecord 미호출`() {
        every { processedEventService.tryRecord(any(), any(), any(), any()) } returns false

        template.process(testEvent, consumerService, acknowledgment) {
            // should not be called
        }

        verify(exactly = 0) { processedEventService.deleteRecord(any(), any()) }
        verify(exactly = 1) { acknowledgment.acknowledge() }
    }

    @Test
    fun `정상 처리 시 deleteRecord 미호출 및 acknowledge 호출`() {
        every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true

        template.process(testEvent, consumerService, acknowledgment) {
            // success
        }

        verify(exactly = 0) { processedEventService.deleteRecord(any(), any()) }
        verify(exactly = 1) { acknowledgment.acknowledge() }
    }

    @Test
    fun `DLQ replay 시나리오 - 동일 eventId로 재처리 가능`() {
        // 1차 처리: non-retryable 예외 → deleteRecord 호출
        every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true

        assertThrows<IllegalArgumentException> {
            template.process(testEvent, consumerService, acknowledgment) {
                throw IllegalArgumentException("first attempt fails")
            }
        }
        verify(exactly = 1) { processedEventService.deleteRecord(testEventId, consumerService) }

        // 2차 처리 (DLQ replay): tryRecord가 true 반환 → 재처리 가능
        every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true

        template.process(testEvent, consumerService, acknowledgment) {
            // replay succeeds
        }

        verify(exactly = 1) { acknowledgment.acknowledge() }
    }
}
