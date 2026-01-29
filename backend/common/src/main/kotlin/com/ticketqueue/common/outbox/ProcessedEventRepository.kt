package com.ticketqueue.common.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import java.time.LocalDateTime
import java.util.UUID

interface ProcessedEventRepository : JpaRepository<ProcessedEvent, ProcessedEventId> {

    fun existsByEventIdAndConsumerService(eventId: UUID, consumerService: String): Boolean

    @Modifying
    fun deleteByProcessedAtBefore(before: LocalDateTime): Int
}
