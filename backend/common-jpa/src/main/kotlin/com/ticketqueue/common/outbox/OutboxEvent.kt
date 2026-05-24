package com.ticketqueue.common.outbox

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Transactional Outbox row.
 *
 * **ID 정책 (SOT):** `id` 는 `BaseEvent.eventId` 와 동일한 UUID로 호출자가 명시 주입한다.
 * outbox row PK ↔ Kafka 발행 payload eventId ↔ Consumer `processed_events.event_id` 가 1:1 매핑된다.
 *
 * **`Persistable<UUID>` 사용 이유:** 명시 id non-null entity는 기본적으로 `SimpleJpaRepository.save()` 가
 * `merge()` (SELECT-then-INSERT) 경로를 탄다. `@Transient _isNew` + `@PostLoad/@PostPersist` 토글로
 * 신규 entity 는 `persist()` (pure INSERT) 경로를 유지하여 N+1 SELECT 를 회피한다.
 */
@Entity
@Access(AccessType.FIELD)
@Table(name = "outbox_events", schema = "common")
class OutboxEvent(
    @Id
    @Column(name = "id")
    private val id: UUID,

    @Column(name = "aggregate_type", nullable = false, length = 50)
    val aggregateType: String,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: UUID,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
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
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
) : Persistable<UUID> {

    @Transient
    private var _isNew: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = _isNew

    @PostLoad
    @PostPersist
    fun markNotNew() {
        _isNew = false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEvent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
