package com.ticketqueue.common.outbox

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID>, OutboxEventRepositoryCustom {

    fun findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
        maxRetryCount: Int
    ): List<OutboxEvent>

    fun findByAggregateTypeAndAggregateId(
        aggregateType: String,
        aggregateId: UUID
    ): List<OutboxEvent>
}
