package com.ticketqueue.reservation.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

enum class ReservationStatus {
    PENDING, CONFIRMED, CANCELLED
}

@Entity
@Table(
    name = "reservations",
    schema = "reservation_service"
)
class Reservation(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "schedule_id", nullable = false)
    val scheduleId: UUID,

    @Column(name = "event_id", nullable = false)
    val eventId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ReservationStatus = ReservationStatus.PENDING,

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 0)
    val totalAmount: BigDecimal,

    @Column(name = "hold_expires_at", nullable = false)
    val holdExpiresAt: LocalDateTime,

    @Column(name = "ticket_number", length = 50)
    var ticketNumber: String? = null,

    @Column(name = "payment_id")
    var paymentId: UUID? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null
) {
    fun confirm(paymentId: UUID, ticketNumber: String) {
        this.status = ReservationStatus.CONFIRMED
        this.paymentId = paymentId
        this.ticketNumber = ticketNumber
    }

    fun cancel() {
        this.status = ReservationStatus.CANCELLED
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reservation) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
