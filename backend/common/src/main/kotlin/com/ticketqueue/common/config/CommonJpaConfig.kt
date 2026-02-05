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
 * ## Repository 사용처
 *
 * ### OutboxEventRepository (Transactional Outbox Pattern)
 * - **Reservation Service**: ReservationCancelled 이벤트 발행 (Producer)
 * - **Payment Service**: PaymentSuccess, PaymentFailed 이벤트 발행 (Producer)
 * - **Event Service**: 사용하지 않음 (Producer가 아님, Consumer 전용)
 *
 * ### ProcessedEventRepository (Consumer 멱등성 보장)
 * - **Reservation Service**: payment.events 구독 (Consumer)
 * - **Event Service**: payment.events, reservation.events 구독 (Consumer)
 * - **Payment Service**: 사용하지 않음 (Producer 전용, Consumer 아님)
 *
 * ## 어노테이션
 * - @ConditionalOnClass: JPA 의존성이 있는 서비스에서만 활성화
 * - @EnableJpaRepositories: Repository 인터페이스 스캔 및 Bean 등록
 * - @EntityScan: Entity 클래스 탐색
 */
@Configuration
@ConditionalOnClass(EntityManagerFactory::class)
@EnableJpaRepositories(basePackages = ["com.ticketqueue.common.outbox"])
@EntityScan(basePackages = ["com.ticketqueue.common.outbox"])
class CommonJpaConfig
