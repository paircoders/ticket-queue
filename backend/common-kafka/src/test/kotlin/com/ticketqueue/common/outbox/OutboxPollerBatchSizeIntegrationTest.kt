package com.ticketqueue.common.outbox

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.UUID

/**
 * OutboxPoller 배치 크기(100) 초과 시 다음 폴링에서 잔여 이벤트를 처리하는지 검증 (TC-OBX-007)
 *
 * **격리 전략 (#302)**:
 * - `outboxPollerService` 빈을 `enabled=false` 로컬 인스턴스(비등록)로 오버라이드: @Scheduled 자동 실행 시 즉시 반환
 * - `@Primary testOutboxPollerService(enabled=true)` 빈을 테스트에 주입하여 수동 폴링 실행
 * - processEvent()의 kafkaTemplate.send().get() 동기 처리 덕분에 Awaitility 불필요
 * - 이전 @Disabled 사유: 다중 Spring 컨텍스트 캐시 환경에서 @Scheduled 폴러 간섭 (#252/#231)
 *
 * **중요: BatchSizeTestConfig 구현 주의사항**
 * - `OutboxPollerProperties` 인스턴스를 Spring Bean으로 등록하면 ConfigurationPropertiesBindingPostProcessor가
 *   setter 바인딩을 시도 → data class(val 프로퍼티)에 setter 없어 실패 (Spring Boot 3.5.x)
 * - 해결: disabled 프로퍼티는 Bean 미등록, outboxPollerService 내부 인라인으로만 사용
 */
@SpringBootTest
@Import(OutboxPollerBatchSizeIntegrationTest.BatchSizeTestConfig::class)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxPollerBatchSizeIntegrationTest {

    /**
     * 배치 크기 테스트 전용 설정:
     * - `testOutboxPollerProperties`: @Primary, enabled=true → testOutboxPollerService에 사용
     * - `outboxPollerService`: @Scheduled 빈 오버라이드, 로컬 disabled 인스턴스 사용 → 자동 폴링 no-op
     * - `testOutboxPollerService`: @Primary → @Autowired 주입, enabled=true → 수동 pollAndPublish() 실행
     *
     * **주의**: OutboxPollerProperties를 별도 @Bean으로 등록하지 말 것.
     * Spring Boot 3.5.x에서 @ConfigurationProperties 클래스의 @Bean 인스턴스에 대해
     * JavaBeanBinder 바인딩을 시도하므로 val 전용 data class는 setter 없어 실패함.
     */
    @TestConfiguration
    class BatchSizeTestConfig {

        @Bean
        @Primary
        fun testOutboxPollerProperties(): OutboxPollerProperties {
            return OutboxPollerProperties(
                enabled = true,
                maxRetryCount = 3,
                batchSize = 100,
                fixedDelay = 1000
            )
        }

        /**
         * @Service outboxPollerService 빈을 오버라이드.
         * disabled 프로퍼티(enabled=false)를 로컬 인스턴스로 생성(Bean 미등록)하여
         * Spring Boot 3.5.x의 @ConfigurationProperties setter 바인딩 오류를 방지.
         * @Scheduled 자동 실행 시 pollAndPublish() → enabled=false → 즉시 반환.
         */
        @Bean("outboxPollerService")
        fun outboxPollerService(
            queryService: OutboxPollerQueryService,
            outboxEventRepository: OutboxEventRepository,
            topicResolver: OutboxTopicResolver,
            kafkaTemplate: KafkaTemplate<String, Any>
        ): OutboxPollerService {
            // Bean 비등록 로컬 인스턴스: ConfigurationPropertiesBindingPostProcessor 대상 제외
            val disabledProperties = OutboxPollerProperties(
                enabled = false,
                maxRetryCount = 3,
                batchSize = 100,
                fixedDelay = 1000
            )
            return OutboxPollerService(
                queryService,
                outboxEventRepository,
                topicResolver,
                kafkaTemplate,
                disabledProperties
            )
        }

        /**
         * 테스트에 주입될 @Primary 서비스: enabled=true → 수동 pollAndPublish() 정상 실행
         */
        @Bean
        @Primary
        fun testOutboxPollerService(
            queryService: OutboxPollerQueryService,
            outboxEventRepository: OutboxEventRepository,
            topicResolver: OutboxTopicResolver,
            kafkaTemplate: KafkaTemplate<String, Any>,
            properties: OutboxPollerProperties
        ): OutboxPollerService {
            return OutboxPollerService(
                queryService,
                outboxEventRepository,
                topicResolver,
                kafkaTemplate,
                properties
            )
        }
    }

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var pollerService: OutboxPollerService

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
    }

    @BeforeEach
    fun setup() {
        outboxEventRepository.deleteAll()
    }

    @Test
    fun `BATCH_SIZE_초과시_다음_폴링에서_처리`() {
        // Given: 101개 이벤트 INSERT (batchSize=100 초과)
        val eventCount = 101
        repeat(eventCount) { i ->
            outboxEventRepository.save(
                OutboxEvent(
                    id = UUID.randomUUID(),
                    aggregateType = "Payment",
                    aggregateId = UUID.randomUUID(),
                    eventType = "PaymentSuccess",
                    payload = """{"orderId": "batch-test-$i"}""",
                    published = false,
                    retryCount = 0,
                    createdAt = LocalDateTime.now().plusNanos(i * 100_000L)
                )
            )
        }

        // When: 1차 수동 폴링 — processEvent()가 kafkaTemplate.send().get() 동기 처리이므로
        //       pollAndPublish() 반환 후 DB 상태 즉시 확정 (Awaitility 불필요)
        // outboxPollerService(자동 스케줄러) 는 enabled=false 이므로 간섭 없음
        pollerService.pollAndPublish()

        // Then: batchSize=100이므로 1차 폴링에서 100개 발행, 나머지 1개 미발행
        outboxEventRepository.countByPublishedTrue() shouldBe 100L
        outboxEventRepository.countByPublishedFalse() shouldBe 1L

        // When: 2차 수동 폴링 — 잔여 1개 처리
        pollerService.pollAndPublish()

        // Then: 101개 모두 발행 완료
        outboxEventRepository.countByPublishedTrue() shouldBe 101L
        outboxEventRepository.countByPublishedFalse() shouldBe 0L
    }
}
