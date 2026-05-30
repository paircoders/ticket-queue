package com.ticketqueue.common.outbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID

/**
 * Outbox 폴링 쿼리(findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc)의 제외 조건을
 * 실제 PostgreSQL 로 검증하는 repository 슬라이스 테스트.
 *
 * **범위 (#251):** retry/DLQ 발행 상태머신(발행 성공/실패 → 재시도 → DLQ 이동 → published 마킹)은
 * OutboxPollerServiceTest 가 KafkaTemplate 를 MockK 로 제어해 이미 결정적으로 단위 검증하고, 실제
 * producer→broker→consumer 경로는 OutboxPollerIntegrationTest / EdgeCase 가 커버한다. 따라서
 * 여기서는 단위 테스트가 잡지 못하는 "실제 JPA 쿼리의 retryCount/published 제외 조건"만 검증한다.
 *
 * 이전의 kafka.stop()/start() 기반 DLQ/Retry 통합 테스트는 (a) 단위 테스트와 중복이고 (b) 단일
 * 브로커로는 '원본 발행 실패 / DLQ 발행 성공'을 재현할 수 없으며 (c) 컨테이너 재시작 시 mapped port
 * 재할당으로 flaky 하여 제거했다. @DataJpaTest 슬라이스는 Kafka/스케줄러 빈을 로드하지 않으므로
 * 백그라운드 폴러 간섭이나 KafkaTemplate 와이어링 문제가 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class OutboxPollerRetryIntegrationTest {

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    companion object {
        private const val MAX_RETRY = 3
        private const val BATCH = 100

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:18-alpine"))
            .apply {
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
            // OutboxEventRecorderIntegrationTest 와 동일: Hibernate 가 스키마/테이블을 생성 (initScript 미사용)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.jpa.properties.hibernate.default_schema") { "common" }
            registry.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces") { "true" }
        }
    }

    @BeforeEach
    fun setup() {
        outboxEventRepository.deleteAll()
    }

    private fun fetch() =
        outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
            MAX_RETRY,
            PageRequest.of(0, BATCH)
        )

    @Test
    fun `retryCount 가 maxRetry 이상이면 폴링 쿼리에서 제외`() {
        val saved = outboxEventRepository.save(event(retryCount = MAX_RETRY, published = false))

        fetch().any { it.id == saved.id } shouldBe false
    }

    @Test
    fun `retryCount 가 maxRetry 미만이면 폴링 쿼리에 포함`() {
        val saved = outboxEventRepository.save(event(retryCount = MAX_RETRY - 1, published = false))

        fetch().any { it.id == saved.id } shouldBe true
    }

    @Test
    fun `published true 이벤트는 폴링 쿼리에서 제외`() {
        val saved = outboxEventRepository.save(event(retryCount = 0, published = true))

        fetch().any { it.id == saved.id } shouldBe false
    }

    private fun event(retryCount: Int, published: Boolean): OutboxEvent =
        OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = "Payment",
            aggregateId = UUID.randomUUID(),
            eventType = "PaymentSuccess",
            payload = """{"test": "data"}""",
            published = published,
            retryCount = retryCount,
            createdAt = LocalDateTime.now()
        )
}
