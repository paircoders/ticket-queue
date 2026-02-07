package com.ticketqueue.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.utils.KafkaTestUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "outbox.poller.enabled=true",
        "spring.kafka.producer.bootstrap-servers=\${kafka.bootstrap-servers}",
        "spring.kafka.consumer.bootstrap-servers=\${kafka.bootstrap-servers}"
    ]
)
@ActiveProfiles("test")
@Testcontainers
class OutboxPollerDlqIntegrationTest {

    companion object {
        @Container
        val postgresContainer = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18"))
            .apply {
                withDatabaseName("testdb")
                withUsername("testuser")
                withPassword("testpass")
                withInitScript("db_init/init.sql")
            }

        @Container
        val kafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.0"))

        @JvmStatic
        @DynamicPropertySource
        fun overrideProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
            registry.add("spring.datasource.username", postgresContainer::getUsername)
            registry.add("spring.datasource.password", postgresContainer::getPassword)
            registry.add("kafka.bootstrap-servers", kafkaContainer::getBootstrapServers)
        }
    }

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPollerService: OutboxPollerService

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>

    private val objectMapper = ObjectMapper().apply {
        findAndRegisterModules()
    }

    @BeforeEach
    fun setup() {
        outboxEventRepository.deleteAll()
    }

    @AfterEach
    fun cleanup() {
        outboxEventRepository.deleteAll()
    }

    @Test
    fun `Payment_DLQ_dlq_payment_토픽_전송`() {
        // Given: retryCount=2인 Payment 이벤트 INSERT
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val payload = createPaymentPayload(eventId, aggregateId, 50000)

        val event = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Payment",
            aggregateId = aggregateId,
            eventType = "PaymentSuccess",
            payload = payload,
            published = false,
            retryCount = 2,
            createdAt = LocalDateTime.now()
        )
        outboxEventRepository.save(event)

        // When: Kafka 중단 상태에서 재시도 → Kafka 재시작
        kafkaContainer.stop()

        // Trigger poll (will fail and move to DLQ)
        outboxPollerService.pollAndPublish()

        // Kafka 재시작
        kafkaContainer.start()

        // Wait for Kafka to be ready
        await()
            .atMost(10, TimeUnit.SECONDS)
            .until { kafkaContainer.isRunning }

        // Then: dlq.payment 토픽에서 메시지 확인
        val dlqMessages = consumeFromDlqTopic("dlq.payment")
        dlqMessages.size shouldBe 1

        val dlqPayload = objectMapper.readValue<Map<String, Any>>(dlqMessages.first())
        dlqPayload["eventId"] shouldBe eventId.toString()
        dlqPayload["eventType"] shouldBe "PaymentSuccess"

        // Verify event is marked as published
        val updatedEvent = outboxEventRepository.findById(event.id!!).get()
        updatedEvent.published shouldBe true
        updatedEvent.retryCount shouldBe 3
    }

    @Test
    fun `Reservation_DLQ_dlq_reservation_토픽_전송`() {
        // Given: retryCount=2인 Reservation 이벤트 INSERT
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val payload = createReservationPayload(eventId, aggregateId)

        val event = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Reservation",
            aggregateId = aggregateId,
            eventType = "ReservationCancelled",
            payload = payload,
            published = false,
            retryCount = 2,
            createdAt = LocalDateTime.now()
        )
        outboxEventRepository.save(event)

        // When: Kafka 중단 상태에서 재시도 → Kafka 재시작
        kafkaContainer.stop()

        // Trigger poll (will fail and move to DLQ)
        outboxPollerService.pollAndPublish()

        // Kafka 재시작
        kafkaContainer.start()

        // Wait for Kafka to be ready
        await()
            .atMost(10, TimeUnit.SECONDS)
            .until { kafkaContainer.isRunning }

        // Then: dlq.reservation 토픽에서 메시지 확인
        val dlqMessages = consumeFromDlqTopic("dlq.reservation")
        dlqMessages.size shouldBe 1

        val dlqPayload = objectMapper.readValue<Map<String, Any>>(dlqMessages.first())
        dlqPayload["eventId"] shouldBe eventId.toString()
        dlqPayload["eventType"] shouldBe "ReservationCancelled"

        // Verify event is marked as published
        val updatedEvent = outboxEventRepository.findById(event.id!!).get()
        updatedEvent.published shouldBe true
        updatedEvent.retryCount shouldBe 3
    }

    @Test
    fun `DLQ_메시지_payload_원본과_동일`() {
        // Given: Payment 이벤트 INSERT (특정 eventId, correlationId 포함)
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        val correlationId = UUID.randomUUID()
        val originalAmount = 75000
        val payload = createDetailedPaymentPayload(eventId, aggregateId, correlationId, originalAmount)

        val event = OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Payment",
            aggregateId = aggregateId,
            eventType = "PaymentSuccess",
            payload = payload,
            published = false,
            retryCount = 0,
            createdAt = LocalDateTime.now()
        )
        outboxEventRepository.save(event)

        // When: Kafka 중단 → retryCount=2로 수동 업데이트 → 재시도 실패
        kafkaContainer.stop()

        // Manually set retryCount to 2 to trigger DLQ on next poll
        val updatedEvent = outboxEventRepository.findById(event.id!!).get()
        updatedEvent.retryCount = 2
        outboxEventRepository.save(updatedEvent)

        // Trigger poll (will fail and move to DLQ)
        outboxPollerService.pollAndPublish()

        // Kafka 재시작
        kafkaContainer.start()

        // Wait for Kafka to be ready
        await()
            .atMost(10, TimeUnit.SECONDS)
            .until { kafkaContainer.isRunning }

        // Then: DLQ 메시지의 payload가 원본과 정확히 일치
        val dlqMessages = consumeFromDlqTopic("dlq.payment")
        dlqMessages.size shouldBe 1

        val dlqPayload = objectMapper.readValue<Map<String, Any>>(dlqMessages.first())

        // Verify all critical fields
        dlqPayload["eventId"] shouldBe eventId.toString()
        dlqPayload["eventType"] shouldBe "PaymentSuccess"
        dlqPayload["aggregateId"] shouldBe aggregateId.toString()
        dlqPayload["aggregateType"] shouldBe "Payment"

        val metadata = dlqPayload["metadata"] as Map<*, *>
        metadata["correlationId"] shouldBe correlationId.toString()

        val payloadData = dlqPayload["payload"] as Map<*, *>
        payloadData["amount"] shouldBe originalAmount

        // Verify event is marked as published
        val finalEvent = outboxEventRepository.findById(event.id!!).get()
        finalEvent.published shouldBe true
        finalEvent.publishedAt shouldNotBe null
        finalEvent.retryCount shouldBe 3
    }

    private fun consumeFromDlqTopic(topic: String): List<String> {
        val consumerProps = KafkaTestUtils.consumerProps(
            kafkaContainer.bootstrapServers,
            "test-dlq-consumer-${UUID.randomUUID()}",
            "true"
        ).apply {
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }

        val consumer = KafkaConsumer<String, String>(consumerProps)
        consumer.subscribe(listOf(topic))

        val messages = mutableListOf<String>()

        // Poll with timeout
        await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(500))
            .until {
                val records = consumer.poll(Duration.ofMillis(1000))
                records.forEach { record ->
                    messages.add(record.value())
                }
                messages.isNotEmpty()
            }

        consumer.close()
        return messages
    }

    private fun createPaymentPayload(eventId: UUID, aggregateId: UUID, amount: Int): String {
        val payload = mapOf(
            "eventId" to eventId.toString(),
            "eventType" to "PaymentSuccess",
            "aggregateId" to aggregateId.toString(),
            "aggregateType" to "Payment",
            "version" to "v1",
            "timestamp" to LocalDateTime.now().toString(),
            "metadata" to mapOf(
                "correlationId" to UUID.randomUUID().toString(),
                "causationId" to UUID.randomUUID().toString(),
                "userId" to UUID.randomUUID().toString()
            ),
            "payload" to mapOf(
                "amount" to amount
            )
        )
        return objectMapper.writeValueAsString(payload)
    }

    private fun createReservationPayload(eventId: UUID, aggregateId: UUID): String {
        val payload = mapOf(
            "eventId" to eventId.toString(),
            "eventType" to "ReservationCancelled",
            "aggregateId" to aggregateId.toString(),
            "aggregateType" to "Reservation",
            "version" to "v1",
            "timestamp" to LocalDateTime.now().toString(),
            "metadata" to mapOf(
                "correlationId" to UUID.randomUUID().toString(),
                "causationId" to UUID.randomUUID().toString(),
                "userId" to UUID.randomUUID().toString()
            ),
            "payload" to mapOf(
                "reservationId" to aggregateId.toString(),
                "reason" to "PAYMENT_FAILED"
            )
        )
        return objectMapper.writeValueAsString(payload)
    }

    private fun createDetailedPaymentPayload(
        eventId: UUID,
        aggregateId: UUID,
        correlationId: UUID,
        amount: Int
    ): String {
        val payload = mapOf(
            "eventId" to eventId.toString(),
            "eventType" to "PaymentSuccess",
            "aggregateId" to aggregateId.toString(),
            "aggregateType" to "Payment",
            "version" to "v1",
            "timestamp" to LocalDateTime.now().toString(),
            "metadata" to mapOf(
                "correlationId" to correlationId.toString(),
                "causationId" to UUID.randomUUID().toString(),
                "userId" to UUID.randomUUID().toString()
            ),
            "payload" to mapOf(
                "amount" to amount,
                "paymentMethod" to "CARD",
                "paymentKey" to "test_payment_key_${UUID.randomUUID()}"
            )
        )
        return objectMapper.writeValueAsString(payload)
    }
}
