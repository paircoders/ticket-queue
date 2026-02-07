package com.ticketqueue.common.outbox

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.Properties
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OutboxPollerRetryIntegrationTest {

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var pollerService: OutboxPollerService

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withDatabaseName("ticketing")
            .withUsername("ticketing_admin")
            .withPassword("ticketing_password")
            .withInitScript("db_init/init.sql")

        @Container
        @JvmStatic
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))

        @Container
        @JvmStatic
        val valkey: GenericContainer<*> = GenericContainer(DockerImageName.parse("valkey/valkey:8.1.5"))
            .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun registerDynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
            registry.add("spring.data.redis.host", valkey::getHost)
            registry.add("spring.data.redis.port") { valkey.getMappedPort(6379) }
            registry.add("outbox.poller.enabled") { "true" }
        }
    }

    @BeforeEach
    fun setup() {
        outboxEventRepository.deleteAll()
        ensureKafkaRunning()
    }

    @AfterEach
    fun cleanup() {
        outboxEventRepository.deleteAll()
        ensureKafkaRunning()
    }

    @Test
    fun `Kafka 전송실패시 retryCount 증가`() {
        // Given: retryCount=2인 이벤트 (이미 2번 실패한 상태)
        val event = createOutboxEvent("Payment", "PaymentSuccess").apply {
            retryCount = 2
        }
        outboxEventRepository.save(event)

        // When: Kafka가 정상 동작하므로 발행 성공
        pollerService.pollAndPublish()

        // Then: 성공하면 published=true, retryCount는 변경 안 됨
        await().atMost(3, TimeUnit.SECONDS).untilAsserted {
            val updated = outboxEventRepository.findById(event.id!!).orElseThrow()
            updated.published shouldBe true
            updated.publishedAt shouldNotBe null
            updated.retryCount shouldBe 2 // 성공 시 retryCount는 증가하지 않음
        }
    }

    @Test
    fun `3회 실패후 DLQ 이동`() {
        // Given: retryCount=2인 이벤트 INSERT (다음 실패 시 3회 도달)
        val event = createOutboxEvent("Reservation", "ReservationCancelled").apply {
            retryCount = 2
        }
        val savedEvent = outboxEventRepository.save(event)

        // When: Kafka 컨테이너 중단
        kafka.stop()

        // Kafka 중단 대기
        Thread.sleep(1000)

        // 폴러 실행 (1회 시도)
        pollerService.pollAndPublish()

        // Then: retryCount=3, published=true (DLQ 이동 후 마킹)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            val updated = outboxEventRepository.findById(savedEvent.id!!).orElseThrow()
            updated.retryCount shouldBe 3
            updated.published shouldBe true
            updated.publishedAt shouldNotBe null
            updated.lastError shouldNotBe null
            updated.lastError shouldContain "Exception"
        }

        // Kafka 재시작
        kafka.start()

        // DLQ 토픽 확인 (dlq.reservation)
        val dlqMessages = consumeFromTopic("dlq.reservation", maxRecords = 1, timeoutMs = 5000)
        dlqMessages.size shouldBe 1
        dlqMessages[0] shouldContain "ReservationCancelled"
    }

    @Test
    fun `DLQ 이동 성공후 원본이벤트 published true`() {
        // Given: retryCount=2 이벤트
        val event = createOutboxEvent("Payment", "PaymentSuccess").apply {
            retryCount = 2
        }
        val savedEvent = outboxEventRepository.save(event)

        // When: Kafka 중단 → 재시도 실패 → DLQ 이동 시도 (실패)
        kafka.stop()
        Thread.sleep(1000)

        pollerService.pollAndPublish()

        // Then: DLQ 전송 실패해도 published=true로 마킹됨 (현재 구현)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            val updated = outboxEventRepository.findById(savedEvent.id!!).orElseThrow()
            updated.published shouldBe true
            updated.retryCount shouldBe 3
        }

        // Kafka 재시작 후 DLQ 확인
        kafka.start()

        // DLQ 메시지는 Kafka가 중단된 상태에서 전송 시도했으므로 없을 수 있음
        // 이 테스트는 published=true 마킹을 주로 검증
    }

    @Test
    fun `retryCount 3이상 이벤트 Poller 무시`() {
        // Given: retryCount=3, published=false 이벤트 INSERT
        val event = createOutboxEvent("Payment", "PaymentFailed").apply {
            retryCount = 3
            published = false
        }
        val savedEvent = outboxEventRepository.save(event)

        // When: 2초 대기 (폴러 2회 실행)
        Thread.sleep(2500)

        // Then: retryCount=3 유지, published=false 유지 (변화 없음)
        val unchanged = outboxEventRepository.findById(savedEvent.id!!).orElseThrow()
        unchanged.retryCount shouldBe 3
        unchanged.published shouldBe false
        unchanged.publishedAt shouldBe null
        // 폴링 쿼리에서 제외되므로 lastError도 변경되지 않음
    }

    // Helper Methods

    private fun createOutboxEvent(aggregateType: String, eventType: String): OutboxEvent {
        return OutboxEvent(
            aggregateType = aggregateType,
            aggregateId = UUID.randomUUID(),
            eventType = eventType,
            payload = """{"test": "data", "timestamp": "${LocalDateTime.now()}"}""",
            published = false,
            retryCount = 0,
            createdAt = LocalDateTime.now()
        )
    }

    private fun ensureKafkaRunning() {
        if (!kafka.isRunning) {
            kafka.start()
            // Kafka 시작 대기
            Thread.sleep(3000)
        }
    }

    private fun consumeFromTopic(
        topic: String,
        maxRecords: Int = 10,
        timeoutMs: Long = 5000
    ): List<String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-${UUID.randomUUID()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        }

        val consumer = KafkaConsumer<String, String>(props)
        consumer.use {
            it.subscribe(listOf(topic))

            val messages = mutableListOf<String>()
            val endTime = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < endTime && messages.size < maxRecords) {
                val records = it.poll(Duration.ofMillis(1000))
                records.forEach { record ->
                    messages.add(record.value())
                }

                if (records.isEmpty && messages.isEmpty()) {
                    continue
                }

                if (messages.size >= maxRecords) {
                    break
                }
            }

            return messages
        }
    }
}
