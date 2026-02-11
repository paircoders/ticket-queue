package com.ticketqueue.common.config

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Querydsl 설정
 * - JPAQueryFactory Bean을 제공하여 타입 안전한 JPQL 쿼리 작성 지원
 * - Custom Repository 구현체에서 벌크 연산 등에 사용
 */
@Configuration
class QuerydslConfig(
    @PersistenceContext
    private val entityManager: EntityManager
) {
    @Bean
    fun jpaQueryFactory(): JPAQueryFactory = JPAQueryFactory(entityManager)
}
