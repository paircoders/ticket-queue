package com.ticketqueue.payment.service

import com.ticketqueue.common.event.EventMetadata
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortonePreRegisterRequest
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.client.ReservationServiceClient.ReservationDetailResponse
import com.ticketqueue.payment.client.ReservationServiceClient.ReservationStatus
import com.ticketqueue.payment.dto.PaymentDto.CreateRequest
import com.ticketqueue.payment.dto.PaymentDto.CreateResponse
import com.ticketqueue.payment.entity.Payment
import com.ticketqueue.payment.entity.PaymentStatus
import com.ticketqueue.payment.exception.PaymentException
import com.ticketqueue.payment.repository.PaymentRepository
import feign.FeignException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
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
    private val portoneProperties: PortoneProperties,
    private val outboxEventRecorder: OutboxEventRecorder,
    private val transactionTemplate: TransactionTemplate,
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

        val payment = try {
            paymentRepository.save(
                Payment(
                    reservationId = request.reservationId,
                    userId = userId,
                    paymentKey = paymentKey,
                    amount = request.amount,
                    paymentMethod = request.paymentMethod
                )
            )
        } catch (e: DataIntegrityViolationException) {
            throw PaymentException(ErrorCode.PAYMENT_ALREADY_EXISTS)
        }

        try {
            portoneClient.preRegisterPayment(
                paymentId = paymentKey,
                request = PortonePreRegisterRequest(
                    storeId = storeId,
                    totalAmount = request.amount.longValueExact()
                ),
                token = portoneTokenService.getAccessToken()
            )
        } catch (e: Exception) {
            val reason = "PortOne pre-register failed: ${e.message}"
            // payment 는 위 첫 save() 가 자체 트랜잭션으로 즉시 커밋되며 detached 상태가 된다.
            // 아래 save() 는 SimpleJpaRepository 의 merge() 경로로 UPDATE 한 행만 발행하고,
            // recordPaymentFailed() 의 outbox INSERT 와 같은 트랜잭션에서 원자적으로 커밋된다.
            transactionTemplate.executeWithoutResult {
                payment.markFailed(reason)
                paymentRepository.save(payment)
                recordPaymentFailed(payment, reason)
            }
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
        reservation: ReservationDetailResponse,
        userId: UUID,
        requestedAmount: BigDecimal
    ) {
        if (reservation.userId != userId) throw PaymentException(ErrorCode.FORBIDDEN)
        if (reservation.status != ReservationStatus.PENDING) throw PaymentException(ErrorCode.RESERVATION_NOT_PAYABLE)
        if (!LocalDateTime.now(ZoneOffset.UTC).isBefore(reservation.holdExpiresAt)) throw PaymentException(ErrorCode.HOLD_EXPIRED)
        if (reservation.totalAmount.compareTo(requestedAmount) != 0) throw PaymentException(ErrorCode.PAYMENT_AMOUNT_MISMATCH)
    }

    /**
     * Transactional Outbox 발행 헬퍼 — PaymentSuccess.
     *
     * 호출자는 활성 트랜잭션 내부에서 호출해야 한다 ([OutboxEventRecorder] PROPAGATION_MANDATORY).
     * payment 는 markSuccess() 가 이미 호출되어 paidAt / portoneTransactionId 가 채워진 상태여야 한다.
     * 결제 승인 API (#59) 가 confirm 트랜잭션 내부에서 호출하기 위한 재사용 진입점.
     */
    private fun recordPaymentSuccess(payment: Payment, reservation: ReservationDetailResponse) {
        val paidAt = requireNotNull(payment.paidAt) { "payment.paidAt must be set before recording PaymentSuccess" }
        val transactionId = requireNotNull(payment.portoneTransactionId) { "payment.portoneTransactionId must be set before recording PaymentSuccess" }
        outboxEventRecorder.record(
            PaymentSuccessEvent(
                aggregateId = payment.id!!,
                reservationId = payment.reservationId,
                paymentKey = payment.paymentKey,
                amount = payment.amount,
                paidAt = paidAt,
                scheduleId = reservation.scheduleId,
                seatIds = reservation.seatIds,
                portoneTransactionId = transactionId,
                metadata = EventMetadata(userId = payment.userId)
            )
        )
    }

    /**
     * Transactional Outbox 발행 헬퍼 — PaymentFailed.
     *
     * 호출자는 활성 트랜잭션 내부에서 호출해야 한다 ([OutboxEventRecorder] PROPAGATION_MANDATORY).
     * createPayment 의 PortOne pre-register 실패 분기 / 결제 승인 실패 분기에서 공통 사용.
     */
    private fun recordPaymentFailed(payment: Payment, reason: String) {
        outboxEventRecorder.record(
            PaymentFailedEvent(
                aggregateId = payment.id!!,
                reservationId = payment.reservationId,
                reason = reason,
                metadata = EventMetadata(userId = payment.userId)
            )
        )
    }
}
