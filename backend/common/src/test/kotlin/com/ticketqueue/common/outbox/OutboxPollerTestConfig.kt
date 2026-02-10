package com.ticketqueue.common.outbox

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate

/**
 * 통합 테스트 전용 Outbox Poller Configuration
 *
 * **목적:**
 * - @ConditionalOnProperty를 우회하여 OutboxPollerService 빈을 무조건 생성
 * - Testcontainers 초기화 순서 문제 해결 (PostgreSQL 시작 전 Spring Context 초기화 방지)
 * - 통합 테스트 환경에서 Outbox Pattern 검증을 위한 설정
 *
 * **사용법:**
 * - 통합 테스트 클래스에 @Import(OutboxPollerTestConfig::class) 추가
 *
 * **해결한 문제:**
 * - Testcontainers가 시작되기 전에 @ConditionalOnProperty가 평가되면서
 *   PostgreSQL JDBC URL 조회 시도 → "Mapped port can only be obtained after the container is started" 에러
 * - OutboxPollerService를 @Primary 빈으로 직접 생성하여 조건 평가를 우회
 */
@TestConfiguration
class OutboxPollerTestConfig {

    /**
     * 테스트용 OutboxPollerProperties 빈 생성
     */
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
     * 테스트용 OutboxPollerService 빈 생성 (@ConditionalOnProperty 우회)
     * - @Primary: 메인 코드의 OutboxPollerService보다 우선
     * - @ConditionalOnProperty가 없으므로 Testcontainers 시작 순서와 무관하게 생성 가능
     */
    @Bean
    @Primary
    fun testOutboxPollerService(
        queryService: OutboxPollerQueryService,
        outboxEventRepository: OutboxEventRepository,
        topicResolver: OutboxTopicResolver,
        @Qualifier("dlqKafkaTemplate") kafkaTemplate: KafkaTemplate<String, Any>,
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
