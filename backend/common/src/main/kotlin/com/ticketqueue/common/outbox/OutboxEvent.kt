package com.ticketqueue.common.outbox

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "outbox_events", schema = "common")
class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,

    @Column(name = "aggregate_type", nullable = false, length = 50)
    val aggregateType: String,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: UUID,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payload: String,

    @Column(name = "published", nullable = false)
    var published: Boolean = false,

    @Column(name = "published_at")
    var publishedAt: LocalDateTime? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEvent) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
