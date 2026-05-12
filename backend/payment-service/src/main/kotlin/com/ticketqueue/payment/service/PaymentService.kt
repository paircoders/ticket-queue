package com.ticketqueue.payment.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortonePreRegisterRequest
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.client.ReservationServiceClient.ReservationStatus
import com.ticketqueue.payment.dto.PaymentDto.CreateRequest
import com.ticketqueue.payment.dto.PaymentDto.CreateResponse
import com.ticketqueue.payment.entity.Payment
import com.ticketqueue.payment.entity.PaymentStatus
import com.ticketqueue.payment.exception.PaymentException
import com.ticketqueue.payment.repository.PaymentRepository
import feign.FeignException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
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
    private val storeId: String = portoneProperties.storeId
        ?: error("external.portone.store-id is required")
    private val channelKey: String = portoneProperties.channelKey
        ?: error("external.portone.channel-key is required")

    fun createPayment(userId: UUID, request: CreateRequest): CreateResponse {
        if (paymentRepository.existsByReservationIdAndStatusIn(
                request.reservationId, listOf(PaymentStatus.PENDING, PaymentStatus.SUCCESS)
            )
        ) {
            throw PaymentException(ErrorCode.PAYMENT_ALREADY_EXISTS)
        }

        val reservation = try {
            reservationServiceClient.getReservation(request.reservationId)
        } catch (e: FeignException.NotFound) {
            throw PaymentException(ErrorCode.RESERVATION_NOT_FOUND)
        } catch (e: FeignException) {
            throw PaymentException(ErrorCode.INTERNAL_SERVER_ERROR)
        }

        validateReservation(reservation, userId, request.amount)

        val paymentKey = UUID.randomUUID().toString()

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

    private fun validateReservation(
        reservation: ReservationServiceClient.ReservationDetailResponse,
        userId: UUID,
        requestedAmount: BigDecimal
    ) {
        if (reservation.userId != userId) throw PaymentException(ErrorCode.FORBIDDEN)
        if (reservation.status != ReservationStatus.PENDING.name) throw PaymentException(ErrorCode.RESERVATION_NOT_PAYABLE)
        if (!LocalDateTime.now(ZoneOffset.UTC).isBefore(reservation.holdExpiresAt)) throw PaymentException(ErrorCode.HOLD_EXPIRED)
        if (reservation.totalAmount.compareTo(requestedAmount) != 0) throw PaymentException(ErrorCode.PAYMENT_AMOUNT_MISMATCH)
    }
}
