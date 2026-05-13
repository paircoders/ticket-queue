package com.ticketqueue.payment.controller

import com.ticketqueue.payment.dto.PaymentDto.CreateRequest
import com.ticketqueue.payment.dto.PaymentDto.CreateResponse
import com.ticketqueue.payment.service.PaymentService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/payments")
class PaymentController(
    private val paymentService: PaymentService
) {

    @PostMapping
    fun createPayment(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody @Valid request: CreateRequest
    ): CreateResponse {
        return paymentService.createPayment(userId, request)
    }
}
