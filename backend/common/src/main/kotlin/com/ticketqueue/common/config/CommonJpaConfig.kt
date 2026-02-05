package com.ticketqueue.common.config

import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Common 모듈의 JPA Repository 설정
 *
 * ProcessedEvent, OutboxEvent Entity와 Repository를 Spring Bean으로 등록합니다.
 *
 * @ConditionalOnClass: JPA 의존성이 있는 서비스에서만 활성화
 * @EnableJpaRepositories: Repository 인터페이스 스캔 및 Bean 등록
 * @EntityScan: Entity 클래스 탐색
 */
@Configuration
@ConditionalOnClass(EntityManagerFactory::class)
@EnableJpaRepositories(basePackages = ["com.ticketqueue.common.outbox"])
@EntityScan(basePackages = ["com.ticketqueue.common.outbox"])
class CommonJpaConfig
