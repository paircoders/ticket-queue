package com.ticketqueue.reservation.integration

import com.ticketqueue.common.outbox.OutboxCleanupBatchService
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.common.outbox.OutboxEventRepository
import com.ticketqueue.common.outbox.OutboxPollerService
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Reservation Service ApplicationContext 가 common-kafka / common-jpa 의 Outbox 인프라 빈들을
 * 정상 로드하는지 검증하는 smoke test.
 *
 * 핵심 검증:
 * - OutboxEventRecorder (#228) 빈이 ApplicationContext 에 등록되는가
 * - OutboxPollerService (Producer) / OutboxCleanupBatchService 빈이 로드되는가
 * - OutboxEventRepository JPA repository 가 common 패키지에서 스캔되는가 (#55 활성화 핵심)
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.config.import=",
        "spring.cloud.aws.region.static=us-east-1",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "spring.cloud.aws.secretsmanager.enabled=false",
        // SmokeTest 는 빈 등록 검증이 목적이므로 cleanup 도 활성화하여 모든 인프라 빈을 확인한다
        "outbox.cleanup.enabled=true"
    ]
)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("Outbox 인프라 빈 로딩 smoke test")
class OutboxInfraWiringTest {

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

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(6379) }
        }
    }

    // required=true (기본값) 로 두면 빈이 누락된 경우 ApplicationContext 로드 실패와 함께
    // 명확한 NoSuchBeanDefinitionException 이 보고된다. null check 보다 진단이 빠르다.
    @Autowired private lateinit var outboxEventRecorder: OutboxEventRecorder
    @Autowired private lateinit var outboxEventRepository: OutboxEventRepository
    @Autowired private lateinit var outboxPollerService: OutboxPollerService
    @Autowired private lateinit var outboxCleanupBatchService: OutboxCleanupBatchService

    @Test
    @DisplayName("OutboxEventRecorder 빈이 ApplicationContext 에 로드된다")
    fun outboxEventRecorderIsRegistered() {
        outboxEventRecorder shouldNotBe null
    }

    @Test
    @DisplayName("OutboxEventRepository 가 common 패키지에서 JPA 스캔되어 로드된다")
    fun outboxEventRepositoryIsScanned() {
        outboxEventRepository shouldNotBe null
    }

    @Test
    @DisplayName("OutboxPollerService 빈이 로드된다 (Producer 활성화 검증)")
    fun outboxPollerServiceIsRegistered() {
        outboxPollerService shouldNotBe null
    }

    @Test
    @DisplayName("OutboxCleanupBatchService 빈이 로드된다 (7일 retention 정리 활성화)")
    fun outboxCleanupBatchServiceIsRegistered() {
        outboxCleanupBatchService shouldNotBe null
    }
}
