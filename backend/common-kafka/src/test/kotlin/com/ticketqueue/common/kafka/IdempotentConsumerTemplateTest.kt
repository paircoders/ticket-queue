package com.ticketqueue.common.kafka

import com.ticketqueue.common.event.BaseEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.kafka.support.Acknowledgment
import java.util.UUID
import java.util.concurrent.TimeoutException

class IdempotentConsumerTemplateTest : DescribeSpec({

    val processedEventService = mockk<ProcessedEventService>()
    val acknowledgment = mockk<Acknowledgment>()
    val template = IdempotentConsumerTemplate(processedEventService)

    beforeEach {
        clearMocks(processedEventService, acknowledgment)
    }

    fun testEvent(): BaseEvent = object : BaseEvent(
        eventType = "TEST_EVENT",
        aggregateId = UUID.randomUUID(),
        aggregateType = "TEST_AGGREGATE",
    ) {}

    describe("IdempotentConsumerTemplate.process()") {

        context("중복 이벤트 (tryRecord = false)") {
            it("businessLogic을 실행하지 않고 acknowledge만 호출한다") {
                every { processedEventService.tryRecord(any(), any(), any(), any()) } returns false
                every { acknowledgment.acknowledge() } just runs

                var called = false
                template.process(testEvent(), "test-service", acknowledgment) { called = true }

                called shouldBe false
                verify { acknowledgment.acknowledge() }
                verify(exactly = 0) { processedEventService.deleteRecord(any(), any()) }
            }
        }

        context("신규 이벤트 정상 처리 (tryRecord = true)") {
            it("businessLogic 실행 후 acknowledge 호출") {
                every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true
                every { acknowledgment.acknowledge() } just runs

                var called = false
                template.process(testEvent(), "test-service", acknowledgment) { called = true }

                called shouldBe true
                verify { acknowledgment.acknowledge() }
                verify(exactly = 0) { processedEventService.deleteRecord(any(), any()) }
            }
        }

        context("retryable 예외 발생 (TimeoutException)") {
            it("deleteRecord 호출 후 예외 rethrow") {
                every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true
                every { processedEventService.deleteRecord(any(), any()) } just runs

                shouldThrow<TimeoutException> {
                    template.process(testEvent(), "test-service", acknowledgment) {
                        throw TimeoutException("timeout")
                    }
                }

                verify { processedEventService.deleteRecord(any(), "test-service") }
                verify(exactly = 0) { acknowledgment.acknowledge() }
            }
        }

        context("non-retryable 예외 발생 (IllegalArgumentException)") {
            it("deleteRecord 호출 후 예외 rethrow — DLQ replay 차단") {
                every { processedEventService.tryRecord(any(), any(), any(), any()) } returns true
                every { processedEventService.deleteRecord(any(), any()) } just runs

                shouldThrow<IllegalArgumentException> {
                    template.process(testEvent(), "test-service", acknowledgment) {
                        throw IllegalArgumentException("invalid")
                    }
                }

                verify { processedEventService.deleteRecord(any(), "test-service") }
                verify(exactly = 0) { acknowledgment.acknowledge() }
            }
        }
    }
})
