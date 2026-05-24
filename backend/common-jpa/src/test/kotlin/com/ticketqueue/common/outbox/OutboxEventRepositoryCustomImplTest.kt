package com.ticketqueue.common.outbox

import com.querydsl.jpa.impl.JPAQueryFactory
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
@Import(OutboxEventRepositoryCustomImplTest.QuerydslTestConfig::class)
class OutboxEventRepositoryCustomImplTest {

    @TestConfiguration
    class QuerydslTestConfig {
        @Bean
        fun queryFactory(entityManager: EntityManager) = JPAQueryFactory(entityManager)

        @Bean
        fun outboxEventRepositoryCustom(queryFactory: JPAQueryFactory): OutboxEventRepositoryCustom =
            OutboxEventRepositoryCustomImpl(queryFactory)
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
        }
    }

    @Autowired
    lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private fun outboxEvent(
        published: Boolean = false,
        publishedAt: LocalDateTime? = null,
        aggregateType: String = "TestAggregate"
    ): OutboxEvent = OutboxEvent(
        id = UUID.randomUUID(),
        aggregateType = aggregateType,
        aggregateId = UUID.randomUUID(),
        eventType = "TestEvent",
        payload = """{"key":"value"}""",
        published = published,
        publishedAt = publishedAt,
    )

    @Test
    fun `deletePublishedEventsBefore - aggregateType과 publishedAt 조건에 맞는 레코드만 삭제`() {
        val cutoff = LocalDateTime.now()

        // 삭제 대상: published=true, publishedAt < cutoff
        val toDelete = outboxEventRepository.save(
            outboxEvent(published = true, publishedAt = cutoff.minusHours(1), aggregateType = "Payment")
        )
        // 삭제 안됨: published=false
        val notPublished = outboxEventRepository.save(
            outboxEvent(published = false, publishedAt = cutoff.minusHours(1), aggregateType = "Payment")
        )
        // 삭제 안됨: publishedAt >= cutoff
        val notYet = outboxEventRepository.save(
            outboxEvent(published = true, publishedAt = cutoff.plusHours(1), aggregateType = "Payment")
        )
        // 삭제 안됨: aggregateType 다름
        val otherType = outboxEventRepository.save(
            outboxEvent(published = true, publishedAt = cutoff.minusHours(1), aggregateType = "Reservation")
        )
        entityManager.flush()
        entityManager.clear()

        val deleted = outboxEventRepository.deletePublishedEventsBefore("Payment", cutoff)

        deleted shouldBe 1L
        outboxEventRepository.findAll().map { it.id } shouldBe listOf(
            notPublished.id, notYet.id, otherType.id
        ).sortedBy { it.toString() }.let {
            outboxEventRepository.findAll().map { e -> e.id }
        }
        outboxEventRepository.existsById(toDelete.id) shouldBe false
        outboxEventRepository.existsById(notPublished.id) shouldBe true
        outboxEventRepository.existsById(notYet.id) shouldBe true
        outboxEventRepository.existsById(otherType.id) shouldBe true
    }

    @Test
    fun `deleteAllPublishedEventsBefore - 모든 aggregateType의 published 이벤트 삭제`() {
        val cutoff = LocalDateTime.now()

        val payment = outboxEventRepository.save(
            outboxEvent(published = true, publishedAt = cutoff.minusHours(1), aggregateType = "Payment")
        )
        val reservation = outboxEventRepository.save(
            outboxEvent(published = true, publishedAt = cutoff.minusHours(2), aggregateType = "Reservation")
        )
        val notPublished = outboxEventRepository.save(
            outboxEvent(published = false, publishedAt = null, aggregateType = "Payment")
        )
        entityManager.flush()
        entityManager.clear()

        val deleted = outboxEventRepository.deleteAllPublishedEventsBefore(cutoff)

        deleted shouldBe 2L
        outboxEventRepository.existsById(payment.id) shouldBe false
        outboxEventRepository.existsById(reservation.id) shouldBe false
        outboxEventRepository.existsById(notPublished.id) shouldBe true
    }

    @Test
    fun `미발행 이벤트는 삭제되지 않음`() {
        val cutoff = LocalDateTime.now()

        val unpublished = outboxEventRepository.save(
            outboxEvent(published = false, publishedAt = null)
        )
        entityManager.flush()
        entityManager.clear()

        val deleted = outboxEventRepository.deleteAllPublishedEventsBefore(cutoff)

        deleted shouldBe 0L
        outboxEventRepository.existsById(unpublished.id) shouldBe true
    }
}
