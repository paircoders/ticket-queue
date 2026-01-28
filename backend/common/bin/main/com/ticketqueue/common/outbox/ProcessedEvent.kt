package com.ticketqueue.common.outbox

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "processed_events", schema = "common")
class ProcessedEvent(
    @Id
    @Column(name = "event_id")
    val eventId: UUID,

    @Column(name = "consumer_group", nullable = false)
    val consumerGroup: String,

    @Column(name = "processed_at", nullable = false)
    val processedAt: LocalDateTime = LocalDateTime.now()
)
