package com.ticketqueue.payment.dto

import com.ticketqueue.payment.entity.PaymentMethod
import com.ticketqueue.payment.entity.PaymentStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class PaymentDto {

    data class CreateRequest(
        @field:NotNull val reservationId: UUID,
        @field:NotNull @field:Positive val amount: BigDecimal,
        val paymentMethod: PaymentMethod = PaymentMethod.CARD
    )

    data class CreateResponse(
        val paymentId: UUID,
        val amount: BigDecimal,
        val storeId: String,
        val channelKey: String,
        val paymentKey: String
    )

    data class ConfirmRequest(
        @field:NotNull val reservationId: UUID,
        @field:NotNull val paymentId: UUID,
        @field:NotBlank val paymentKey: String,
        @field:NotBlank val transactionId: String,
        @field:NotNull @field:Positive val amount: BigDecimal
    )

    data class ConfirmResponse(
        val paymentId: UUID,
        val status: PaymentStatus,
        val paidAt: LocalDateTime?
    )
}
