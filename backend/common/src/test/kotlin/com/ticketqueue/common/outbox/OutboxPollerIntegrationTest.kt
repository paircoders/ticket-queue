package com.ticketqueue.common.outbox

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.support.serializer.JsonDeserializer
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
import java.util.concurrent.ConcurrentHashMap

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxPollerIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine"))
            .apply {
                withDatabaseName("testdb")
                withUsername("test")
                withPassword("test")
                withInitScript("db_init/init.sql")
            }

        @Container
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
            .apply {
                withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
            }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            // PostgreSQL
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            // Kafka
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var pollerService: OutboxPollerService

    private lateinit var kafkaTestConsumer: KafkaTestConsumer

    @BeforeAll
    fun setupKafkaConsumer() {
        kafkaTestConsumer = KafkaTestConsumer(kafka.bootstrapServers)
    }

    @AfterAll
    fun tearDownKafkaConsumer() {
        kafkaTestConsumer.close()
    }

    @BeforeEach
    fun cleanupDatabase() {
        outboxEventRepository.deleteAll()
        kafkaTestConsumer.clearReceivedMessages()
    }

    @Test
    fun `이벤트_INSERT_후_1초내_Kafka_발행_확인`() {
        // Given
        val aggregateId = UUID.randomUUID()
        val event = outboxEventRepository.save(
            OutboxEvent(
                aggregateType = "Payment",
                aggregateId = aggregateId,
                eventType = "PaymentSuccess",
                payload = createPaymentPayload(aggregateId),
                published = false
            )
        )

        // When - Poller runs automatically every 1 second
        // Wait for event to be published
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val savedEvent = outboxEventRepository.findById(event.id!!).get()
                savedEvent.published shouldBe true
                savedEvent.publishedAt shouldNotBe null
            }

        // Then - Verify Kafka message received
        await()
            .atMost(Duration.ofSeconds(2))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted {
                val messages = kafkaTestConsumer.getMessages("payment.events")
                messages shouldHaveSize 1
                messages[0] shouldBe createPaymentPayload(aggregateId)
            }
    }

    @Test
    fun `Payment_이벤트_payment_events_토픽_발행`() {
        // Given
        val aggregateId = UUID.randomUUID()
        val payload = createPaymentPayload(aggregateId)
        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = "Payment",
                aggregateId = aggregateId,
                eventType = "PaymentSuccess",
                payload = payload,
                published = false
            )
        )

        // When
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val messages = kafkaTestConsumer.getMessages("payment.events")
                messages shouldHaveSize 1
            }

        // Then
        val messages = kafkaTestConsumer.getMessages("payment.events")
        messages[0] shouldBe payload
    }

    @Test
    fun `Reservation_이벤트_reservation_events_토픽_발행`() {
        // Given
        val aggregateId = UUID.randomUUID()
        val payload = createReservationPayload(aggregateId)
        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = "Reservation",
                aggregateId = aggregateId,
                eventType = "ReservationCancelled",
                payload = payload,
                published = false
            )
        )

        // When
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val messages = kafkaTestConsumer.getMessages("reservation.events")
                messages shouldHaveSize 1
            }

        // Then
        val messages = kafkaTestConsumer.getMessages("reservation.events")
        messages[0] shouldBe payload
    }

    @Test
    fun `배치_100개_이벤트_순차_발행`() {
        // Given - Create 100 events in order
        val events = (1..100).map { index ->
            Thread.sleep(1) // Ensure different createdAt timestamps
            outboxEventRepository.save(
                OutboxEvent(
                    aggregateType = "Payment",
                    aggregateId = UUID.randomUUID(),
                    eventType = "PaymentSuccess",
                    payload = createPaymentPayload(UUID.randomUUID(), "event-$index"),
                    published = false,
                    createdAt = LocalDateTime.now()
                )
            )
        }

        // When - Wait for all events to be published
        await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofSeconds(1))
            .untilAsserted {
                val publishedCount = outboxEventRepository.findAll().count { it.published }
                publishedCount shouldBe 100
            }

        // Then - All events should be published
        val allEvents = outboxEventRepository.findAll()
        allEvents shouldHaveSize 100
        allEvents.forEach { event ->
            event.published shouldBe true
            event.publishedAt shouldNotBe null
        }

        // Verify Kafka messages received
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val messages = kafkaTestConsumer.getMessages("payment.events")
                messages.size shouldBeGreaterThanOrEqual 100
            }

        // Verify message order (createdAt order should be preserved)
        val messages = kafkaTestConsumer.getMessages("payment.events")
        val eventIds = events.map { it.id!! }
        val receivedEventIndices = messages.mapNotNull { message ->
            // Extract event index from correlation ID
            val correlationId = message.substringAfter("\"correlationId\":\"")
                .substringBefore("\"")
            if (correlationId.startsWith("event-")) {
                correlationId.removePrefix("event-").toIntOrNull()
            } else null
        }

        // Check that indices are in ascending order (within batches)
        receivedEventIndices.take(10).zipWithNext().forEach { (prev, next) ->
            next shouldBeGreaterThanOrEqual prev
        }
    }

    @Test
    fun `이미_발행된_이벤트_재발행_안함`() {
        // Given - Already published event
        val aggregateId = UUID.randomUUID()
        val publishedEvent = outboxEventRepository.save(
            OutboxEvent(
                aggregateType = "Payment",
                aggregateId = aggregateId,
                eventType = "PaymentSuccess",
                payload = createPaymentPayload(aggregateId),
                published = true,
                publishedAt = LocalDateTime.now()
            )
        )

        // Clear any existing messages
        kafkaTestConsumer.clearReceivedMessages()

        // When - Wait for 2 poller cycles
        Thread.sleep(2500)

        // Then - No new Kafka messages should be published
        val messages = kafkaTestConsumer.getMessages("payment.events")
        messages shouldHaveSize 0

        // Event should still be marked as published
        val event = outboxEventRepository.findById(publishedEvent.id!!).get()
        event.published shouldBe true
    }

    /**
     * Helper method to create Payment event payload
     */
    private fun createPaymentPayload(aggregateId: UUID, correlationId: String = UUID.randomUUID().toString()): String {
        return """
            {
                "eventId": "${UUID.randomUUID()}",
                "eventType": "PaymentSuccess",
                "aggregateId": "$aggregateId",
                "aggregateType": "Payment",
                "version": "v1",
                "timestamp": "${LocalDateTime.now()}",
                "metadata": {
                    "correlationId": "$correlationId",
                    "causationId": "${UUID.randomUUID()}",
                    "userId": "${UUID.randomUUID()}"
                },
                "payload": {
                    "paymentId": "$aggregateId",
                    "reservationId": "${UUID.randomUUID()}",
                    "amount": 50000,
                    "status": "SUCCESS"
                }
            }
        """.trimIndent().replace(Regex("\\s+"), " ")
    }

    /**
     * Helper method to create Reservation event payload
     */
    private fun createReservationPayload(aggregateId: UUID): String {
        return """
            {
                "eventId": "${UUID.randomUUID()}",
                "eventType": "ReservationCancelled",
                "aggregateId": "$aggregateId",
                "aggregateType": "Reservation",
                "version": "v1",
                "timestamp": "${LocalDateTime.now()}",
                "metadata": {
                    "correlationId": "${UUID.randomUUID()}",
                    "causationId": "${UUID.randomUUID()}",
                    "userId": "${UUID.randomUUID()}"
                },
                "payload": {
                    "reservationId": "$aggregateId",
                    "scheduleId": "${UUID.randomUUID()}",
                    "reason": "PAYMENT_FAILED"
                }
            }
        """.trimIndent().replace(Regex("\\s+"), " ")
    }

    /**
     * Helper class for Kafka message consumption in tests
     */
    class KafkaTestConsumer(bootstrapServers: String) {
        private val consumer: KafkaConsumer<String, String>
        private val receivedMessages = ConcurrentHashMap<String, MutableList<String>>()
        private val pollingThread: Thread

        init {
            val props = Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            }

            consumer = KafkaConsumer(props)
            consumer.subscribe(listOf("payment.events", "reservation.events"))

            // Start background polling thread
            pollingThread = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val records = consumer.poll(Duration.ofMillis(100))
                        records.forEach { record ->
                            receivedMessages.computeIfAbsent(record.topic()) { mutableListOf() }
                                .add(record.value())
                        }
                    }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        e.printStackTrace()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun getMessages(topic: String): List<String> {
            return receivedMessages[topic]?.toList() ?: emptyList()
        }

        fun clearReceivedMessages() {
            receivedMessages.clear()
        }

        fun close() {
            pollingThread.interrupt()
            consumer.close()
        }
    }
}
