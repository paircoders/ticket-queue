package com.ticketqueue.payment.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "payments", schema = "payment_service")
class Payment(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "reservation_id", nullable = false)
    val reservationId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "payment_key", nullable = false, length = 200, unique = true)
    val paymentKey: String,

    @Column(name = "amount", nullable = false, precision = 10, scale = 0)
    val amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PaymentStatus = PaymentStatus.PENDING,

    @Column(name = "portone_transaction_id", length = 100)
    var portoneTransactionId: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "portone_response", columnDefinition = "jsonb")
    var portoneResponse: String? = null,

    @Column(name = "failure_reason", columnDefinition = "text")
    var failureReason: String? = null,

    @Column(name = "paid_at")
    var paidAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime? = null,
) {
    init {
        require(paymentKey.isNotBlank()) { "paymentKey must not be blank" }
        require(paymentKey.length <= 200) { "paymentKey must not exceed 200 characters" }
        require(amount > BigDecimal.ZERO) { "amount must be positive" }
    }

    fun markSuccess(transactionId: String, response: String, paidAt: LocalDateTime) {
        require(status.canTransitionTo(PaymentStatus.SUCCESS)) {
            "Cannot transition from $status to SUCCESS"
        }
        this.status = PaymentStatus.SUCCESS
        this.portoneTransactionId = transactionId
        this.portoneResponse = response
        this.paidAt = paidAt
    }

    fun markFailed(reason: String, response: String? = null) {
        require(status.canTransitionTo(PaymentStatus.FAILED)) {
            "Cannot transition from $status to FAILED"
        }
        this.status = PaymentStatus.FAILED
        this.failureReason = reason
        response?.let { this.portoneResponse = it }
    }

    fun refund() {
        require(status.canTransitionTo(PaymentStatus.REFUNDED)) {
            "Cannot transition from $status to REFUNDED"
        }
        this.status = PaymentStatus.REFUNDED
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Payment) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0
}
