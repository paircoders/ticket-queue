package com.ticketqueue.common.outbox

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

data class ProcessedEventId(
    val eventId: UUID = UUID.randomUUID(),
    val consumerService: String = ""
) : Serializable

@Entity
@Table(name = "processed_events", schema = "common")
@IdClass(ProcessedEventId::class)
class ProcessedEvent(
    @Id
    @Column(name = "event_id")
    val eventId: UUID,

    @Id
    @Column(name = "consumer_service", nullable = false, length = 50)
    val consumerService: String,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: UUID,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "processed_at", nullable = false)
    val processedAt: LocalDateTime = LocalDateTime.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessedEvent) return false
        return eventId == other.eventId && consumerService == other.consumerService
    }

    override fun hashCode(): Int {
        var result = eventId.hashCode()
        result = 31 * result + consumerService.hashCode()
        return result
    }
}
