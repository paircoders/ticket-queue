package com.ticketqueue.payment.controller

import com.ticketqueue.common.dto.PageResponse
import com.ticketqueue.payment.dto.PaymentDto.ConfirmRequest
import com.ticketqueue.payment.dto.PaymentDto.ConfirmResponse
import com.ticketqueue.payment.dto.PaymentDto.CreateRequest
import com.ticketqueue.payment.dto.PaymentDto.CreateResponse
import com.ticketqueue.payment.dto.PaymentDto.DetailResponse
import com.ticketqueue.payment.dto.PaymentDto.ListItem
import com.ticketqueue.payment.service.PaymentService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/payments")
@Validated
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

    @PostMapping("/confirm")
    fun confirmPayment(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody @Valid request: ConfirmRequest
    ): ConfirmResponse {
        return paymentService.confirmPayment(userId, request)
    }

    /**
     * 결제 상세 조회 (REQ-PAY-014, spec §1.4).
     */
    @GetMapping("/{paymentId}")
    fun getPayment(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable paymentId: UUID,
    ): DetailResponse {
        return paymentService.getPayment(userId, paymentId)
    }

    /**
     * 내 결제 내역 페이징 조회 (REQ-PAY-015, spec §1.3).
     *
     * `size` 상한 100 / 하한 1 강제 — Plan §7 Decision 3 의 paging 컨벤션.
     */
    @GetMapping
    fun listPayments(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): PageResponse<ListItem> {
        return paymentService.listPayments(userId, page, size)
    }
}
