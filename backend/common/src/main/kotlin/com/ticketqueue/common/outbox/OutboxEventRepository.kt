package com.ticketqueue.common.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import java.time.LocalDateTime
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    fun findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
        maxRetryCount: Int
    ): List<OutboxEvent>

    fun findByAggregateTypeAndAggregateId(
        aggregateType: String,
        aggregateId: UUID
    ): List<OutboxEvent>

    // 전체 서비스의 발행 완료 이벤트 삭제 (전역 정리용)
    @Modifying
    fun deleteByPublishedTrueAndPublishedAtBefore(before: LocalDateTime): Int

    // 특정 aggregateType의 발행 완료 이벤트만 삭제 (서비스별 정리용)
    @Modifying
    fun deleteByAggregateTypeAndPublishedTrueAndPublishedAtBefore(
        aggregateType: String,
        before: LocalDateTime
    ): Int
}
