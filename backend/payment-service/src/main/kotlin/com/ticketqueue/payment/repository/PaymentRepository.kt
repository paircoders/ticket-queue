package com.ticketqueue.payment.repository

import com.ticketqueue.payment.entity.Payment
import com.ticketqueue.payment.entity.PaymentStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

interface PaymentRepository : JpaRepository<Payment, UUID> {
    fun findByPaymentKey(paymentKey: String): Optional<Payment>
    fun existsByReservationIdAndStatusIn(reservationId: UUID, statuses: List<PaymentStatus>): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Optional<Payment>
}
