package com.ticketqueue.reservation.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.outbox.OutboxEventRepository
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationSeat
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import com.ticketqueue.reservation.service.ReservationService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reservation Service e2e Outbox + Kafka 통합 테스트.
 *
 * 검증 포커스 (#55 + #228):
 * 1. cancelReservation() 호출 → outbox_events INSERT (동일 트랜잭션)
 * 2. OutboxPollerService 가 1초 주기로 reservation.events 토픽 발행
 * 3. Kafka Header 에 eventType=ReservationCancelled / aggregateType=Reservation 포함
 * 4. outbox_events 의 published=true, published_at 갱신
 * 5. **outbox row.id == payload.eventId** — #228 의 1:1 추적성 핵심 검증
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.config.import=",
        "spring.cloud.aws.region.static=us-east-1",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "spring.cloud.aws.secretsmanager.enabled=false",
        // application-test.yml 의 outbox 비활성화 / kafka 제외를 e2e 검증을 위해 override
        "outbox.poller.enabled=true",
        "outbox.poller.fixed-delay=500",
        "outbox.cleanup.enabled=false",
        "spring.autoconfigure.exclude="
    ]
)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Reservation Outbox + Kafka e2e 통합 테스트")
class ReservationOutboxPollerIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ticketing")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/init.sql")

        @Container
        @JvmStatic
        val valkey = GenericContainer("valkey/valkey:8.1.5-alpine3.23")
            .withExposedPorts(6379)

        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(6379) }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var reservationRepository: ReservationRepository
    @Autowired private lateinit var reservationSeatRepository: ReservationSeatRepository
    @Autowired private lateinit var outboxEventRepository: OutboxEventRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockkBean private lateinit var eventServiceClient: EventServiceClient

    private lateinit var kafkaConsumer: KafkaConsumer<String, String>

    @BeforeEach
    fun cleanupState() {
        outboxEventRepository.deleteAll()
        reservationSeatRepository.deleteAll()
        reservationRepository.deleteAll()

        val props = java.util.Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
            put(ConsumerConfig.GROUP_ID_CONFIG, "reservation-outbox-e2e-test-${UUID.randomUUID()}")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
        }
        kafkaConsumer = KafkaConsumer(props)
        kafkaConsumer.subscribe(listOf("reservation.events"))
        // partition assignment 완료까지 명시적 대기 — 단일 poll(100ms) 만으로는 CI 환경에서
        // consumer group rebalance 가 끝나기 전에 메시지가 발행될 수 있어 메시지가 누락된다.
        val assignmentDeadline = System.currentTimeMillis() + 10_000
        while (kafkaConsumer.assignment().isEmpty() && System.currentTimeMillis() < assignmentDeadline) {
            kafkaConsumer.poll(Duration.ofMillis(200))
        }
    }

    @org.junit.jupiter.api.AfterEach
    fun closeConsumer() {
        if (::kafkaConsumer.isInitialized) kafkaConsumer.close()
    }

    @Test
    @DisplayName("cancelReservation → reservation.events Kafka 메시지 발행, Header 와 payload 가 정합하다")
    fun cancelReservationProducesKafkaMessageWithMatchingEventId() {
        // Given — PENDING 예매 + 좌석 1건 시드
        val userId = UUID.randomUUID()
        val scheduleId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val seatId = UUID.randomUUID()
        val reservation = reservationRepository.save(
            Reservation(
                userId = userId,
                scheduleId = scheduleId,
                eventId = eventId,
                totalAmount = BigDecimal("150000"),
                holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
            )
        )
        reservationSeatRepository.save(
            ReservationSeat(
                reservationId = reservation.id!!,
                seatId = seatId,
                seatNumber = "A-01",
                grade = "VIP",
                price = BigDecimal("150000")
            )
        )
        every { eventServiceClient.getScheduleInfo(scheduleId) } returns EventServiceClient.ScheduleInfoResponse(
            scheduleId = scheduleId,
            eventId = eventId,
            eventStartAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
            eventEndAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).plusHours(2),
            saleStartAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(10),
            saleEndAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1)
        )

        // When — cancelReservation 호출
        reservationService.cancelReservation(userId, reservation.id!!)

        // Then — outbox row published=true 까지 대기
        await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted {
                val all = outboxEventRepository.findAll()
                all.size shouldBe 1
                all[0].published shouldBe true
                all[0].publishedAt shouldNotBe null
            }

        val outboxRow = outboxEventRepository.findAll().single()
        outboxRow.aggregateType shouldBe "Reservation"
        outboxRow.eventType shouldBe "ReservationCancelled"
        outboxRow.aggregateId shouldBe reservation.id

        // Then — Kafka 메시지 수신 및 Header 검증
        val records = pollUntilFound(timeoutSeconds = 10)
        records.size shouldBe 1
        val record = records[0]
        record.headers().lastHeader("eventType")?.value()?.toString(Charsets.UTF_8) shouldBe "ReservationCancelled"
        record.headers().lastHeader("aggregateType")?.value()?.toString(Charsets.UTF_8) shouldBe "Reservation"

        // Then — payload 의 eventId 가 Consumer 멱등성 추적 키로 보존되었는지 검증 (#228)
        // Producer 의 JsonSerializer 가 String payload 를 다시 JSON 으로 인코딩하므로
        // 수신 측에서는 (a) String 추출 → (b) ReservationCancelledEvent 파싱의 2 단계가 필요하다
        val payloadJson = objectMapper.readValue(record.value(), String::class.java)
        val payloadEvent = objectMapper.readValue(payloadJson, ReservationCancelledEvent::class.java)
        payloadEvent.eventId shouldNotBe null
        payloadEvent.aggregateId shouldBe reservation.id
        payloadEvent.userId shouldBe userId
        payloadEvent.reason shouldBe "USER_REQUEST"
    }

    private fun pollUntilFound(timeoutSeconds: Long): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        val collected = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
        while (System.currentTimeMillis() < deadline && collected.isEmpty()) {
            val batch = kafkaConsumer.poll(Duration.ofMillis(500))
            batch.forEach { collected.add(it) }
        }
        return collected
    }
}
