package com.ticketqueue.payment.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortonePreRegisterRequest
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.dto.PaymentDto.CreateRequest
import com.ticketqueue.payment.dto.PaymentDto.CreateResponse
import com.ticketqueue.payment.entity.Payment
import com.ticketqueue.payment.exception.PaymentException
import com.ticketqueue.payment.repository.PaymentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val reservationServiceClient: ReservationServiceClient,
    private val portoneClient: PortoneFeignClient,
    private val portoneTokenService: PortoneTokenService,
    private val portoneProperties: PortoneProperties
) {

    private val log = KotlinLogging.logger {}

    fun createPayment(userId: UUID, request: CreateRequest): CreateResponse {
        val reservation = reservationServiceClient.getReservation(request.reservationId)

        if (reservation.userId != userId) {
            throw PaymentException(ErrorCode.FORBIDDEN)
        }

        if (reservation.status != "PENDING") {
            throw PaymentException(ErrorCode.HOLD_EXPIRED)
        }

        if (!LocalDateTime.now(ZoneOffset.UTC).isBefore(reservation.holdExpiresAt)) {
            throw PaymentException(ErrorCode.HOLD_EXPIRED)
        }

        if (reservation.totalAmount.compareTo(request.amount) != 0) {
            throw PaymentException(ErrorCode.PAYMENT_AMOUNT_MISMATCH)
        }

        val paymentKey = UUID.randomUUID().toString()
        val storeId = portoneProperties.storeId!!
        val channelKey = portoneProperties.channelKey!!

        val payment = paymentRepository.save(
            Payment(
                reservationId = request.reservationId,
                userId = userId,
                paymentKey = paymentKey,
                amount = request.amount,
                paymentMethod = request.paymentMethod
            )
        )

        try {
            portoneClient.preRegisterPayment(
                paymentId = paymentKey,
                request = PortonePreRegisterRequest(
                    storeId = storeId,
                    totalAmount = request.amount.toLong()
                ),
                token = portoneTokenService.getAccessToken()
            )
        } catch (e: Exception) {
            payment.markFailed("PortOne pre-register failed: ${e.message}")
            paymentRepository.save(payment)
            throw PaymentException(ErrorCode.PORTONE_PRE_REGISTER_FAILED)
        }

        log.info { "Payment created: paymentId=${payment.id}, reservationId=${request.reservationId}, userId=$userId" }

        return CreateResponse(
            paymentId = payment.id!!,
            amount = payment.amount,
            storeId = storeId,
            channelKey = channelKey,
            paymentKey = paymentKey
        )
    }
}
