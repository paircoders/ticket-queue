package com.ticketqueue.payment.entity

enum class PaymentStatus {
    PENDING, SUCCESS, FAILED, REFUNDED;

    private companion object {
        val allowedTransitions = mapOf(
            PENDING to setOf(SUCCESS, FAILED),
            SUCCESS to setOf(SUCCESS, REFUNDED),
            FAILED to setOf(FAILED),
            REFUNDED to emptySet<PaymentStatus>(),
        )
    }

    fun canTransitionTo(target: PaymentStatus): Boolean =
        allowedTransitions[this]?.contains(target) ?: false
}
