package com.ticketqueue.payment.dto

import com.ticketqueue.payment.entity.PaymentMethod
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
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
}
