package com.ticketqueue.common.outbox

import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.LocalDateTime

/**
 * Outbox 이벤트 Custom Repository 구현체
 * - Querydsl JPAQueryFactory를 사용한 벌크 DELETE 실행
 * - 단일 SQL로 조건에 맞는 모든 레코드를 원자적으로 삭제
 */
class OutboxEventRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory
) : OutboxEventRepositoryCustom {

    override fun deletePublishedEventsBefore(aggregateType: String, before: LocalDateTime): Long {
        val outbox = QOutboxEvent.outboxEvent
        return queryFactory.delete(outbox)
            .where(
                outbox.aggregateType.eq(aggregateType),
                outbox.published.isTrue,
                outbox.publishedAt.before(before)
            )
            .execute()
    }

    override fun deleteAllPublishedEventsBefore(before: LocalDateTime): Long {
        val outbox = QOutboxEvent.outboxEvent
        return queryFactory.delete(outbox)
            .where(
                outbox.published.isTrue,
                outbox.publishedAt.before(before)
            )
            .execute()
    }
}
