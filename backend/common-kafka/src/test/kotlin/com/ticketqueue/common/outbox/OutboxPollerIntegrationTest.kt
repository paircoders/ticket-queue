package com.ticketqueue.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
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
import java.util.Properties
import java.util.UUID

// TODO: 동일 패턴이 다른 모듈에서 필요해질 경우 :common-kafka:test fixture 로 추출 (별도 follow-up 이슈)
@SpringBootTest
@Import(OutboxPollerTestConfig::class)
@ActiveProfiles("test")
@Testcontainers
// 클래스 종료 시 컨텍스트(+@Scheduled 폴러)를 닫아, 캐시된 컨텍스트의 폴러가 teardown 된 컨테이너로
// 쿼리하며 30s Hikari 타임아웃을 유발하는 cross-context 누수를 방지한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxPollerIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine"))
            .apply {
                withDatabaseName("testdb")
                withUsername("test")
                withPassword("test")
                withInitScript("db_init/init.sql")
            }

        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
            .apply {
                withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
            }

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }

        // 검증된 연속-폴링 consumer (EdgeCase 의 ManualKafkaConsumer 재사용). per-test 새 consumer +
        // subscribe + assignment-wait 패턴은 메시지 0건 수신 race(#231) 가 있어 폐기했다.
        private lateinit var kafkaTestConsumer: ManualKafkaConsumer

        /**
         * 클래스 lifetime 동안 한 번만 실행: 토픽을 명시적으로 사전 생성한다.
         *
         * 이유: subscribe() + assignment-wait + seekToEnd 패턴은 broker 에 토픽이 사전 존재해야
         * partition assignment 가 즉시 완료된다. KAFKA_AUTO_CREATE_TOPICS_ENABLE=true 는
         * producer 의 send() 가 트리거하므로 consumer subscribe 만으로는 토픽 자동 생성이 안 된다.
         * 토픽이 없으면 assignment-wait-loop 가 10s deadline 까지 대기 후 seekToEnd(empty set) 가
         * no-op 으로 끝나, 이후 발행되는 메시지의 수신 시점이 불확정적이 된다.
         */
        @BeforeAll
        @JvmStatic
        fun createTopicsOnce() {
            val adminProps = Properties().apply {
                put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            }
            AdminClient.create(adminProps).use { admin ->
                admin.createTopics(
                    listOf(
                        NewTopic("payment.events", 1, 1.toShort()),
                        NewTopic("reservation.events", 1, 1.toShort())
                    )
                ).all().get()
            }
            // 토픽 사전생성 직후 연속-폴링 consumer 시작 (subscribe 시 토픽이 존재해야 즉시 assignment 완료).
            kafkaTestConsumer = ManualKafkaConsumer(kafka.bootstrapServers)
        }

        @AfterAll
        @JvmStatic
        fun tearDownClass() {
            kafkaTestConsumer.close()
        }
    }

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var pollerService: OutboxPollerService

    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun cleanupAndSetup() {
        outboxEventRepository.deleteAll()
        // 연속-폴링 consumer 의 누적 메시지를 테스트마다 초기화 → payload-level filter 로 inter-test isolation.
        // (직전 테스트 메시지가 broker 에 잔존해도 본 테스트의 payload/aggregateId 매칭만 카운트 — see #231.)
        kafkaTestConsumer.clearMessages()
    }

    @Test
    fun `이벤트_INSERT_후_1초내_Kafka_발행_확인`() {
        // Given
        val aggregateId = UUID.randomUUID()
        val payload = createPaymentPayload(aggregateId)
        val event = outboxEventRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Payment",
                aggregateId = aggregateId,
                eventType = "PaymentSuccess",
                payload = payload,
                published = false
            )
        )

        // When - Poller runs automatically every 1 second
        await()
            .atMost(Duration.ofSeconds(5))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted {
                val savedEvent = outboxEventRepository.findById(event.id).get()
                savedEvent.published shouldBe true
                savedEvent.publishedAt shouldNotBe null
            }

        // Then - Verify Kafka message received (payload-level filter 로 inter-test isolation)
        // jsonb 정규화로 키 순서가 바뀌므로 문자열이 아닌 JsonNode 트리로 비교한다.
        val messages = pollUntilFound("payment.events", expectedCount = 1, timeoutSeconds = 5) { jsonEquals(it, payload) }
        messages shouldHaveSize 1
        jsonEquals(messages[0], payload) shouldBe true
    }

    @Test
    fun `Payment_이벤트_payment_events_토픽_발행`() {
        // Given
        val aggregateId = UUID.randomUUID()
        val payload = createPaymentPayload(aggregateId)
        outboxEventRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Payment",
                aggregateId = aggregateId,
                eventType = "PaymentSuccess",
                payload = payload,
                published = false
            )
        )

        // When/Then — poller(1s cycle) + Kafka 수신을 pollUntilFound 가 함께 대기
        val messages = pollUntilFound("payment.events", expectedCount = 1, timeoutSeconds = 5) { jsonEquals(it, payload) }
        messages shouldHaveSize 1
        jsonEquals(messages[0], payload) shouldBe true
    }

    @Test
    fun `Reservation_이벤트_reservation_events_토픽_발행`() {
        // Given
        val aggregateId = UUID.randomUUID()
        val payload = createReservationPayload(aggregateId)
        outboxEventRepository.save(
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Reservation",
                aggregateId = aggregateId,
                eventType = "ReservationCancelled",
                payload = payload,
                published = false
            )
        )

        // When/Then
        val messages = pollUntilFound("reservation.events", expectedCount = 1, timeoutSeconds = 5) { jsonEquals(it, payload) }
        messages shouldHaveSize 1
        jsonEquals(messages[0], payload) shouldBe true
    }

    @Test
    fun `배치_100개_이벤트_순차_발행`() {
        // Given - Create 100 events in order
        val events = (1..100).map { index ->
            Thread.sleep(1) // Ensure different createdAt timestamps
            outboxEventRepository.save(
                OutboxEvent(
                    id = UUID.randomUUID(),
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

        // Verify Kafka messages received (drain until >=100 of this test's events)
        // filter: 본 테스트가 발행한 'event-N' correlationId 메시지만 카운트 (inter-test bleed 차단)
        val messages = pollUntilFound("payment.events", expectedCount = 100, timeoutSeconds = 15) { msg ->
            correlationIdOf(msg).startsWith("event-")
        }
        messages.size shouldBeGreaterThanOrEqual 100

        // Verify message order (createdAt order should be preserved within batches)
        val receivedEventIndices = messages.mapNotNull { message ->
            val correlationId = correlationIdOf(message)
            if (correlationId.startsWith("event-")) {
                correlationId.removePrefix("event-").toIntOrNull()
            } else null
        }

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
                id = UUID.randomUUID(),
                aggregateType = "Payment",
                aggregateId = aggregateId,
                eventType = "PaymentSuccess",
                payload = createPaymentPayload(aggregateId),
                published = true,
                publishedAt = LocalDateTime.now()
            )
        )

        // When - Wait for 1 poller cycle (1s fixed-delay) + headroom
        Thread.sleep(1100)

        // Then - No new Kafka messages of this test's aggregateId should be published
        // pollUntilFound(..., expectedCount = 0, ...) 는 negative path: deadline 끝까지 drain.
        // filter: 본 테스트의 aggregateId 매칭만 카운트 (inter-test bleed 의 직전 테스트 메시지 무관)
        val messages = pollUntilFound("payment.events", expectedCount = 0, timeoutSeconds = 2) {
            it.contains(aggregateId.toString())
        }
        messages shouldHaveSize 0

        // Event should still be marked as published
        val event = outboxEventRepository.findById(publishedEvent.id).get()
        event.published shouldBe true
    }

    /**
     * 지정 [topic] 의 메시지를 [timeoutSeconds] 초 deadline 안에 [expectedCount] 개 이상 수집할 때까지 polling 한다.
     *
     * 종료 조건:
     * - collected.size >= expectedCount  → 즉시 반환
     * - System.currentTimeMillis() >= deadline → 그 시점까지 모은 결과 그대로 반환
     *
     * [filter] 는 inter-test isolation 용 — 본 테스트가 자신의 aggregateId / payload 매칭 메시지만
     * 카운트하도록 한다. 기본값 `{ true }` 는 모든 메시지를 받아들임.
     *
     * expectedCount = 0 인 negative path 는 deadline 끝까지 drain (early-return 금지) — 호출자는
     * deadline 만료 대기를 전제로 사용해야 한다. (early-return 하면 "0 개를 한 번 poll 했더니 비어있다"
     * 와 "deadline 동안 polling 했는데도 비어있다" 가 구분되지 않는다.)
     */
    private fun pollUntilFound(
        topic: String,
        expectedCount: Int,
        timeoutSeconds: Long,
        filter: (String) -> Boolean = { true }
    ): List<String> {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        var collected = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            // 연속-폴링 consumer 의 누적 메시지를 조회한다. Producer 의 JsonSerializer 가 String payload 를
            // JSON 으로 재인코딩하므로 원본 payload 로 언래핑한 뒤 filter(payload-level isolation)를 적용한다.
            val received = when (topic) {
                "payment.events" -> kafkaTestConsumer.getPaymentMessages()
                "reservation.events" -> kafkaTestConsumer.getReservationMessages()
                else -> emptyList()
            }
            collected = received
                .map { objectMapper.readValue(it, String::class.java) }
                .filter(filter)
            // expectedCount=0(negative path)는 deadline 끝까지 대기 후 빈 결과 반환(early-return 금지).
            if (expectedCount > 0 && collected.size >= expectedCount) break
            Thread.sleep(100)
        }
        return collected
    }

    /**
     * payload 는 jsonb 컬럼을 왕복하며 키 순서/공백이 정규화되므로(PostgreSQL jsonb 는 키를 길이+바이트
     * 순으로 재정렬) 원본 문자열과 정확히 일치하지 않는다. 따라서 문자열 동등성이 아니라 JsonNode 트리
     * 비교(순서 무관)로 의미적 동등성을 확인한다.
     */
    private fun jsonEquals(a: String, b: String): Boolean = objectMapper.readTree(a) == objectMapper.readTree(b)

    /** jsonb 정규화에 견디도록 metadata.correlationId 를 JSON 파싱으로 추출한다. */
    private fun correlationIdOf(json: String): String =
        objectMapper.readTree(json).path("metadata").path("correlationId").asText()

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
}
