package com.ticketqueue.reservation.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(
    name = "reservation_seats",
    schema = "reservation_service"
)
class ReservationSeat(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "reservation_id", nullable = false)
    val reservationId: UUID,

    @Column(name = "seat_id", nullable = false)
    val seatId: UUID,

    @Column(name = "seat_number", nullable = false, length = 20)
    val seatNumber: String,

    @Column(name = "grade", nullable = false, length = 10)
    val grade: String,

    @Column(name = "price", nullable = false, precision = 10, scale = 0)
    val price: BigDecimal,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReservationSeat) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
