package com.ticketqueue.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.LocalDateTime
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "outbox.poller.enabled=true"
    ]
)
@Import(OutboxPollerTestConfig::class)
@ActiveProfiles("test")
@Testcontainers
// 클래스 종료 시 컨텍스트(+@Scheduled 폴러)를 닫아 cross-context 폴러 누수(teardown 된 컨테이너로의
// 30s Hikari 타임아웃)를 방지한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxPollerEdgeCaseIntegrationTest {

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val objectMapper = ObjectMapper()

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgresContainer = PostgreSQLContainer<Nothing>("postgres:18-alpine").apply {
            withDatabaseName("testdb")
            withUsername("testuser")
            withPassword("testpass")
            withInitScript("db_init/init.sql")
        }

        @Container
        @JvmStatic
        val kafkaContainer = KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.9.0")
        )

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafkaContainer.bootstrapServers }
        }

        private lateinit var kafkaTestConsumer: ManualKafkaConsumer

        @BeforeAll
        @JvmStatic
        fun setupAll() {
            kafkaTestConsumer = ManualKafkaConsumer(kafkaContainer.bootstrapServers)
        }

        @AfterAll
        @JvmStatic
        fun tearDownAll() {
            kafkaTestConsumer.close()
        }
    }

    @BeforeEach
    fun setup() {
        outboxEventRepository.deleteAll()
        kafkaTestConsumer.clearMessages()
    }

    @Test
    fun `동시에_여러_이벤트_INSERT_순서보장`() {
        // Given: 50개 이벤트를 1ms 간격으로 INSERT (createdAt이 다르도록)
        val eventCount = 50
        val events = mutableListOf<OutboxEvent>()

        for (i in 1..eventCount) {
            val event = OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Payment",
                aggregateId = UUID.randomUUID(),
                eventType = "PaymentSuccess",
                payload = """{"orderId": "event-$i", "sequenceId": $i}""",
                published = false,
                retryCount = 0,
                createdAt = LocalDateTime.now().plusNanos(i * 1_000_000L) // 1ms 간격
            )
            events.add(outboxEventRepository.save(event))
            Thread.sleep(1) // Ensure distinct createdAt
        }

        // When: Awaitility로 모두 발행될 때까지 대기
        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(Duration.ofMillis(500))
            .until { outboxEventRepository.countByPublishedTrue() == eventCount.toLong() }

        // Then: Kafka 메시지 수신 순서가 createdAt 순서와 일치하는지 확인
        // Producer 의 JsonSerializer 가 String payload 를 JSON 으로 재인코딩하므로 원본으로 언래핑한다.
        val receivedMessages = kafkaTestConsumer.getPaymentMessages()
            .map { objectMapper.readValue(it, String::class.java) }
        receivedMessages shouldHaveSize eventCount

        for (i in 0 until eventCount) {
            val payload = receivedMessages[i]
            val expectedSequence = i + 1
            payload.contains("\"sequenceId\": $expectedSequence") shouldBe true
        }
    }


    @Test
    fun `트랜잭션_롤백시_이벤트_상태_유지`() {
        // Given: Initial count
        val initialCount = outboxEventRepository.count()

        // When: 실제 트랜잭션 경계(TransactionTemplate) 내에서 INSERT 후 Exception 발생.
        // private @Transactional self-invocation 은 Spring AOP 프록시가 적용되지 않아 롤백되지 않으므로,
        // TransactionTemplate 으로 명시적 트랜잭션 경계를 만들어 예외 시 롤백을 검증한다.
        try {
            TransactionTemplate(transactionManager).execute {
                outboxEventRepository.save(
                    OutboxEvent(
                        id = UUID.randomUUID(),
                        aggregateType = "Payment",
                        aggregateId = UUID.randomUUID(),
                        eventType = "PaymentSuccess",
                        payload = """{"test": "rollback"}""",
                        published = false,
                        retryCount = 0,
                        createdAt = LocalDateTime.now()
                    )
                )
                throw RuntimeException("Simulated transaction rollback")
            }
        } catch (e: RuntimeException) {
            // Expected exception → 트랜잭션 롤백
        }

        // Then: DB에 이벤트가 없는지 확인 (롤백됨)
        outboxEventRepository.count() shouldBe initialCount
    }

    @Test
    fun `이벤트_0개시_에러_없음`() {
        // Given: DB에 이벤트 없음
        outboxEventRepository.deleteAll()
        outboxEventRepository.count() shouldBe 0

        // When: 3초 대기 (폴러 3회 실행)
        Thread.sleep(3000)

        // Then: Exception 발생 안 하면 성공
        outboxEventRepository.count() shouldBe 0
    }
}

/**
 * Manual Kafka Consumer for testing (without Spring @KafkaListener)
 */
class ManualKafkaConsumer(bootstrapServers: String) {
    private val paymentMessages = CopyOnWriteArrayList<String>()
    private val reservationMessages = CopyOnWriteArrayList<String>()
    private val consumer: KafkaConsumer<String, String>
    private val consumerThread: Thread

    init {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-${UUID.randomUUID()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
        }
        consumer = KafkaConsumer(props)
        consumer.subscribe(listOf("payment.events", "reservation.events"))

        consumerThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val records = consumer.poll(Duration.ofMillis(100))
                    records.forEach { record ->
                        when (record.topic()) {
                            "payment.events" -> paymentMessages.add(record.value())
                            "reservation.events" -> reservationMessages.add(record.value())
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        consumerThread.start()
    }

    fun getPaymentMessages(): List<String> = paymentMessages.toList()

    fun getReservationMessages(): List<String> = reservationMessages.toList()

    fun clearMessages() {
        paymentMessages.clear()
        reservationMessages.clear()
    }

    fun close() {
        consumerThread.interrupt()
        consumerThread.join(5000)
        consumer.close()
    }
}
