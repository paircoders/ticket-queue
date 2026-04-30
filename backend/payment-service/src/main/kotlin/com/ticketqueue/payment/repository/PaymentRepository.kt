package com.ticketqueue.payment.repository

import com.ticketqueue.payment.entity.Payment
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface PaymentRepository : JpaRepository<Payment, UUID> {
    fun findByPaymentKey(paymentKey: String): Optional<Payment>
}
