package com.ticketqueue.event.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "event_schedules", schema = "event_service")
class EventSchedule(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    val event: Event,

    @Column(name = "play_sequence", nullable = false)
    val playSequence: Int,

    @Column(name = "event_start_at", nullable = false)
    val eventStartAt: LocalDateTime,

    @Column(name = "event_end_at", nullable = false)
    val eventEndAt: LocalDateTime,

    @Column(name = "sale_start_at", nullable = false)
    val saleStartAt: LocalDateTime,

    @Column(name = "sale_end_at", nullable = false)
    val saleEndAt: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: ScheduleStatus = ScheduleStatus.UPCOMING,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventSchedule) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
