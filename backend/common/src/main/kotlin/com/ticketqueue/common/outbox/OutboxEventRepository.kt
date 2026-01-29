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

    @Modifying
    fun deleteByPublishedTrueAndPublishedAtBefore(before: LocalDateTime): Int
}
