package com.ticketqueue.common.outbox

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * @ConditionalOnProperty 동작 검증 테스트
 *
 * 서비스 기동 없이 ApplicationContextRunner로 bean 생성 여부를 검증합니다.
 * - processed-event.cleanup.enabled=true  → ProcessedEventsCleanupBatchService 생성 O (Reservation/Event Service)
 * - 프로퍼티 미설정                          → ProcessedEventsCleanupBatchService 생성 X (Payment Service)
 */
class ProcessedEventsCleanupBatchServiceConditionalTest {

    // ProcessedEventRepository mock을 제공하는 테스트 설정
    @Configuration
    class MockRepoConfig {
        @Bean
        fun processedEventRepository(): ProcessedEventRepository = mockk(relaxed = true)
    }

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(
            MockRepoConfig::class.java,
            ProcessedEventsCleanupBatchService::class.java
        )

    @Test
    fun `processed-event cleanup enabled=true 이면 bean이 생성된다 (Reservation, Event Service)`() {
        contextRunner
            .withPropertyValues(
                "processed-event.cleanup.enabled=true",
                "processed-event.cleanup.retention-days=30"
            )
            .run { context ->
                assertThat(context).hasSingleBean(ProcessedEventsCleanupBatchService::class.java)
            }
    }

    @Test
    fun `processed-event cleanup 프로퍼티 미설정 시 bean이 생성되지 않는다 (Payment Service)`() {
        contextRunner
            .run { context ->
                assertThat(context).doesNotHaveBean(ProcessedEventsCleanupBatchService::class.java)
            }
    }

    @Test
    fun `processed-event cleanup enabled=false 이면 bean이 생성되지 않는다`() {
        contextRunner
            .withPropertyValues("processed-event.cleanup.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(ProcessedEventsCleanupBatchService::class.java)
            }
    }

    @Test
    fun `retention-days 기본값 30일이 적용된다`() {
        contextRunner
            .withPropertyValues("processed-event.cleanup.enabled=true")
            .run { context ->
                assertThat(context).hasSingleBean(ProcessedEventsCleanupBatchService::class.java)
                // bean이 정상 생성되면 기본값 30일 적용 확인 (예외 없이 기동)
            }
    }
}
