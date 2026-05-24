package com.ticketqueue.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.event.EventMetadata
import com.ticketqueue.common.event.ReservationCancelledEvent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.annotation.Rollback
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
@Import(OutboxEventRecorderIntegrationTest.RecorderTestConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Rollback(false)
@DisplayName("OutboxEventRecorder DB round-trip 통합 테스트")
class OutboxEventRecorderIntegrationTest {

    @TestConfiguration
    class RecorderTestConfig {
        @Bean
        fun outboxEventRecorder(
            outboxEventRepository: OutboxEventRepository,
            objectMapper: ObjectMapper
        ): OutboxEventRecorder = OutboxEventRecorder(outboxEventRepository, objectMapper)

        @Bean
        fun txTemplate(txManager: PlatformTransactionManager): TransactionTemplate =
            TransactionTemplate(txManager)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:15-alpine").apply {
            withDatabaseName("testdb")
            withUsername("test")
            withPassword("test")
        }

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.jpa.properties.hibernate.default_schema") { "common" }
            registry.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces") { "true" }
        }
    }

    @Autowired
    lateinit var recorder: OutboxEventRecorder

    @Autowired
    lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var txTemplate: TransactionTemplate

    @AfterEach
    fun cleanUp() {
        outboxEventRepository.deleteAll()
    }

    @Test
    @DisplayName("record() 후 DB에서 event.eventId로 조회 가능 (SOT 라운드트립)")
    fun roundtrip() {
        val event = sampleEvent()

        txTemplate.executeWithoutResult { recorder.record(event) }

        val saved = outboxEventRepository.findById(event.eventId).orElseThrow()
        saved.id shouldBe event.eventId
        saved.aggregateType shouldBe "Reservation"
        saved.aggregateId shouldBe event.aggregateId
        saved.eventType shouldBe "ReservationCancelled"
        saved.published shouldBe false

        val deserialized = objectMapper.readValue(saved.payload, ReservationCancelledEvent::class.java)
        deserialized.eventId shouldBe event.eventId
        deserialized.userId shouldBe event.userId
        deserialized.seatIds shouldBe event.seatIds
    }

    @Test
    @DisplayName("동일 eventId로 두 번 record() 시 PK 충돌 (DataIntegrityViolationException) — producer-side 멱등성")
    fun pkConflictOnDuplicateEventId() {
        val event = sampleEvent()

        txTemplate.executeWithoutResult { recorder.record(event) }

        assertThrows<DataIntegrityViolationException> {
            txTemplate.executeWithoutResult { recorder.record(event) }
        }

        outboxEventRepository.count() shouldBe 1L
    }

    @Test
    @DisplayName("서로 다른 eventId 이벤트 두 건은 충돌 없이 저장된다")
    fun twoDistinctEventsCoexist() {
        val e1 = sampleEvent()
        val e2 = sampleEvent()

        txTemplate.executeWithoutResult { recorder.record(e1) }
        txTemplate.executeWithoutResult { recorder.record(e2) }

        outboxEventRepository.count() shouldBe 2L
        outboxEventRepository.findById(e1.eventId).isPresent shouldBe true
        outboxEventRepository.findById(e2.eventId).isPresent shouldBe true
    }

    private fun sampleEvent() = ReservationCancelledEvent(
        aggregateId = UUID.randomUUID(),
        scheduleId = UUID.randomUUID(),
        seatIds = listOf(UUID.randomUUID()),
        userId = UUID.randomUUID(),
        reason = "USER_REQUEST",
        metadata = EventMetadata(userId = UUID.randomUUID())
    )
}
