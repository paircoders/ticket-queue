package com.ticketqueue.common.outbox

import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow

class OutboxPollerServiceTest {

    private val outboxEventRepository = mockk<OutboxEventRepository>()
    private val kafkaTemplate = mockk<KafkaTemplate<String, Any>>()
    private lateinit var topicResolver: OutboxTopicResolver
    private lateinit var pollerService: OutboxPollerService

    @BeforeEach
    fun setup() {
        clearAllMocks()
        topicResolver = OutboxTopicResolver()
        pollerService = OutboxPollerService(
            outboxEventRepository,
            topicResolver,
            kafkaTemplate
        )
    }

    @Test
    fun `pollAndPublish - no events - does nothing`() {
        // Given
        every {
            outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
                any(),
                any()
            )
        } returns emptyList()

        // When
        pollerService.pollAndPublish()

        // Then
        verify(exactly = 0) { kafkaTemplate.send(any(), any(), any()) }
    }

    @Test
    fun `pollAndPublish - success - marks event as published`() {
        // Given
        val event = createOutboxEvent("Payment", "PaymentSuccess")
        val slot = slot<OutboxEvent>()

        every {
            outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
                any(),
                any()
            )
        } returns listOf(event)

        every {
            kafkaTemplate.send(any(), any(), any())
        } returns CompletableFuture.completedFuture(mockk())

        every {
            outboxEventRepository.save(capture(slot))
        } answers { slot.captured }

        // When
        pollerService.pollAndPublish()

        // Then
        verify { kafkaTemplate.send(eq("payment.events"), any(), any()) }
        slot.captured.published shouldBe true
        slot.captured.publishedAt shouldNotBe null
    }

    @Test
    fun `processEvent - kafka failure - increments retry count`() {
        // Given
        val event = createOutboxEvent("Payment", "PaymentSuccess")
        val slot = slot<OutboxEvent>()
        val future = CompletableFuture<SendResult<String, Any>>()
        future.completeExceptionally(RuntimeException("Kafka unavailable"))

        every { kafkaTemplate.send(any(), any(), any()) } returns future
        every { outboxEventRepository.save(capture(slot)) } answers { slot.captured }

        // When
        pollerService.processEvent(event)

        // Then
        slot.captured.retryCount shouldBe 1
        slot.captured.lastError shouldBe "ExecutionException: java.lang.RuntimeException: Kafka unavailable"
        slot.captured.published shouldBe false
        verify { outboxEventRepository.save(any()) }
    }

    @Test
    fun `processEvent - max retries exceeded - moves to DLQ`() {
        // Given
        val event = createOutboxEvent("Reservation", "ReservationCancelled").apply {
            retryCount = 2
        }
        val slot = slot<OutboxEvent>()
        val mainFuture = CompletableFuture<SendResult<String, Any>>()
        mainFuture.completeExceptionally(RuntimeException("Kafka unavailable"))
        val dlqFuture = CompletableFuture.completedFuture(mockk<SendResult<String, Any>>())

        every { kafkaTemplate.send(eq("reservation.events"), any(), any()) } returns mainFuture
        every { kafkaTemplate.send(eq("dlq.reservation"), any(), any()) } returns dlqFuture
        every { outboxEventRepository.save(capture(slot)) } answers { slot.captured }

        // When
        pollerService.processEvent(event)

        // Then
        slot.captured.retryCount shouldBe 3
        slot.captured.published shouldBe true
        verify { kafkaTemplate.send("reservation.events", any(), any()) }
        verify { kafkaTemplate.send("dlq.reservation", any(), any()) }
        verify { outboxEventRepository.save(any()) }
    }

    @Test
    fun `topicResolver - resolves payment topic correctly`() {
        // When & Then
        topicResolver.resolveTopic("Payment") shouldBe "payment.events"
        topicResolver.resolveDlqTopic("payment.events") shouldBe "dlq.payment"
    }

    @Test
    fun `topicResolver - resolves reservation topic correctly`() {
        // When & Then
        topicResolver.resolveTopic("Reservation") shouldBe "reservation.events"
        topicResolver.resolveDlqTopic("reservation.events") shouldBe "dlq.reservation"
    }

    @Test
    fun `topicResolver - throws on unknown aggregate`() {
        // When & Then
        shouldThrow<IllegalArgumentException> {
            topicResolver.resolveTopic("Unknown")
        }
    }

    @Test
    fun `processEvent - DLQ failure - still marks as published`() {
        // Given: retryCount=2, both main and DLQ send fail
        val event = createOutboxEvent("Payment", "PaymentSuccess").apply {
            retryCount = 2
        }
        val slot = slot<OutboxEvent>()
        val mainFuture = CompletableFuture<SendResult<String, Any>>()
        mainFuture.completeExceptionally(RuntimeException("Kafka unavailable"))
        val dlqFuture = CompletableFuture<SendResult<String, Any>>()
        dlqFuture.completeExceptionally(RuntimeException("DLQ also unavailable"))

        every { kafkaTemplate.send(eq("payment.events"), any(), any()) } returns mainFuture
        every { kafkaTemplate.send(eq("dlq.payment"), any(), any()) } returns dlqFuture
        every { outboxEventRepository.save(capture(slot)) } answers { slot.captured }

        // When
        pollerService.processEvent(event)

        // Then: Even if DLQ fails, published should be true to stop retries
        slot.captured.retryCount shouldBe 3
        slot.captured.published shouldBe true
        slot.captured.lastError shouldBe "ExecutionException: java.lang.RuntimeException: Kafka unavailable"
        verify { kafkaTemplate.send("payment.events", any(), any()) }
        verify { kafkaTemplate.send("dlq.payment", any(), any()) }
    }

    @Test
    fun `pollAndPublish - batch size limit - processes only 100 events`() {
        // Given: 150 events available
        val events = (1..150).map { createOutboxEvent("Payment", "PaymentSuccess") }

        every {
            outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
                3,
                PageRequest.of(0, 100)
            )
        } returns events.take(100)

        every {
            kafkaTemplate.send(any(), any(), any())
        } returns CompletableFuture.completedFuture(mockk())

        every {
            outboxEventRepository.save(any())
        } answers { firstArg() }

        // When
        pollerService.pollAndPublish()

        // Then: Should process exactly 100 events
        verify(exactly = 100) { kafkaTemplate.send(any(), any(), any()) }
        verify(exactly = 100) { outboxEventRepository.save(any()) }
    }

    @Test
    fun `pollAndPublish - multiple events - processes in order`() {
        // Given: 3 events with different createdAt
        val event1 = createOutboxEvent("Payment", "PaymentSuccess")
        val event2 = createOutboxEvent("Reservation", "ReservationCancelled")
        val event3 = createOutboxEvent("Payment", "PaymentFailed")
        val processedEvents = mutableListOf<OutboxEvent>()

        every {
            outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
                any(),
                any()
            )
        } returns listOf(event1, event2, event3)

        every {
            kafkaTemplate.send(any(), any(), any())
        } returns CompletableFuture.completedFuture(mockk())

        every {
            outboxEventRepository.save(capture(processedEvents))
        } answers { processedEvents.last() }

        // When
        pollerService.pollAndPublish()

        // Then: All 3 events should be processed and marked as published
        processedEvents.size shouldBe 3
        processedEvents.all { it.published } shouldBe true
        verify { kafkaTemplate.send("payment.events", any(), event1.payload) }
        verify { kafkaTemplate.send("reservation.events", any(), event2.payload) }
        verify { kafkaTemplate.send("payment.events", any(), event3.payload) }
    }

    @Test
    fun `pollAndPublish - retryCount 3 events excluded from polling`() {
        // Given: Query should exclude retryCount >= 3
        every {
            outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
                3, // maxRetryCount parameter
                PageRequest.of(0, 100)
            )
        } returns emptyList()

        // When
        pollerService.pollAndPublish()

        // Then: Query was called with correct maxRetryCount (3)
        verify {
            outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
                3,
                any()
            )
        }
        verify(exactly = 0) { kafkaTemplate.send(any(), any(), any()) }
    }

    private fun createOutboxEvent(aggregateType: String, eventType: String) = OutboxEvent(
        id = UUID.randomUUID(),
        aggregateType = aggregateType,
        aggregateId = UUID.randomUUID(),
        eventType = eventType,
        payload = """{"test": "data"}""",
        published = false,
        retryCount = 0,
        createdAt = LocalDateTime.now()
    )
}
