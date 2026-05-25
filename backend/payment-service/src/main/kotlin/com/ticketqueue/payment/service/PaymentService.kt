package com.ticketqueue.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.dto.PageResponse
import com.ticketqueue.common.event.EventMetadata
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneCircuitOpenException
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortonePaymentResponse
import com.ticketqueue.common.external.portone.PortonePreRegisterRequest
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.client.ReservationServiceClient.ReservationDetailResponse
import com.ticketqueue.payment.client.ReservationServiceClient.ReservationStatus
import com.ticketqueue.payment.dto.PaymentDto.ConfirmRequest
import com.ticketqueue.payment.dto.PaymentDto.ConfirmResponse
import com.ticketqueue.payment.dto.PaymentDto.CreateRequest
import com.ticketqueue.payment.dto.PaymentDto.CreateResponse
import com.ticketqueue.payment.dto.PaymentDto.DetailResponse
import com.ticketqueue.payment.dto.PaymentDto.ListItem
import com.ticketqueue.payment.entity.Payment
import com.ticketqueue.payment.entity.PaymentStatus
import com.ticketqueue.payment.exception.PaymentException
import com.ticketqueue.payment.repository.PaymentRepository
import feign.FeignException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val objectMapper: ObjectMapper,
    private val paymentMaskingMapper: PaymentMaskingMapper,
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
            val reason = if (e is PortoneCircuitOpenException) {
                "PortOne circuit breaker open"
            } else {
                "PortOne pre-register failed: ${e.message}"
            }
            // payment 는 위 첫 save() 가 자체 트랜잭션으로 즉시 커밋되며 detached 상태가 된다.
            // 아래 save() 는 SimpleJpaRepository 의 merge() 경로로 UPDATE 한 행만 발행하고,
            // recordPaymentFailed() 의 outbox INSERT 와 같은 트랜잭션에서 원자적으로 커밋된다.
            transactionTemplate.executeWithoutResult {
                payment.markFailed(reason)
                paymentRepository.save(payment)
                recordPaymentFailed(payment, reason)
            }
            // CB Open 은 도메인 예외(503) 그대로 전파하여 클라이언트가 재시도 의미를 구분할 수 있게 한다.
            if (e is PortoneCircuitOpenException) throw e
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

    /**
     * 결제 승인 서비스 (REQ-PAY-010)
     *
     * ## 승인 프로세스
     * 1. Payment 조회 — 소유권/요청 본문 정합성/상태 머신으로 조기 거절
     * 2. Reservation 재조회 — hold_expires_at 만료 검증, scheduleId/seatIds 확보
     * 3. PortOne `getPayment(paymentKey)` 호출 — status/amount/transactionId 위변조 검증
     * 4. DB 트랜잭션: PESSIMISTIC_WRITE 락 + 상태 재검증 + markSuccess|markFailed + Outbox 발행
     *
     * PortOne 응답이 PAID 가 아니거나 amount/transactionId 가 어긋나면 markFailed + PaymentFailedEvent 를 같은
     * 트랜잭션으로 기록한 뒤 200 OK + status=FAILED 로 응답한다 (스펙 1.2). PortOne 호출이 FeignException 으로
     * 실패하면 502 PORTONE_API_ERROR 로, Resilience4j CircuitBreaker 가 차단한 경우(CallNotPermittedException →
     * FallbackFactory) 503 PORTONE_CIRCUIT_OPEN 으로 즉시 전파한다 — Payment 는 PENDING 으로 남아 클라이언트가
     * 안전하게 재시도할 수 있다. 동시 confirm race 는 락 재검증으로 차단되어 중복 outbox 발행이 발생하지 않는다.
     */
    fun confirmPayment(userId: UUID, request: ConfirmRequest): ConfirmResponse {
        val payment = paymentRepository.findById(request.paymentId)
            .orElseThrow { PaymentException(ErrorCode.RESOURCE_NOT_FOUND) }

        if (payment.userId != userId) throw PaymentException(ErrorCode.FORBIDDEN)
        if (payment.paymentKey != request.paymentKey
            || payment.reservationId != request.reservationId
            || payment.amount.compareTo(request.amount) != 0
        ) {
            throw PaymentException(ErrorCode.INVALID_INPUT)
        }

        when (payment.status) {
            PaymentStatus.SUCCESS -> throw PaymentException(ErrorCode.PAYMENT_ALREADY_EXISTS)
            PaymentStatus.FAILED, PaymentStatus.REFUNDED -> throw PaymentException(ErrorCode.PAYMENT_FAILED)
            PaymentStatus.PENDING -> Unit
        }

        val reservation = try {
            reservationServiceClient.getReservation(payment.reservationId)
        } catch (e: FeignException.NotFound) {
            throw PaymentException(ErrorCode.RESERVATION_NOT_FOUND)
        } catch (e: FeignException) {
            throw PaymentException(ErrorCode.INTERNAL_SERVER_ERROR)
        }

        if (!LocalDateTime.now(ZoneOffset.UTC).isBefore(reservation.holdExpiresAt)) {
            throw PaymentException(ErrorCode.HOLD_EXPIRED)
        }

        val portoneResponse: PortonePaymentResponse = try {
            portoneClient.getPayment(
                paymentId = payment.paymentKey,
                storeId = storeId,
                token = portoneTokenService.getAccessToken()
            )
        } catch (e: PortoneCircuitOpenException) {
            // CircuitBreaker Open → 503 그대로 전파. Payment 는 PENDING 유지 → 클라이언트 재시도 가능.
            throw e
        } catch (e: FeignException) {
            throw PaymentException(ErrorCode.PORTONE_API_ERROR)
        }

        val responseJson = objectMapper.writeValueAsString(portoneResponse)
        val expectedAmount = payment.amount.longValueExact()
        val failureReason: String? = when {
            portoneResponse.status != "PAID" -> "PORTONE_STATUS_${portoneResponse.status}"
            portoneResponse.amount.total != expectedAmount -> "AMOUNT_MISMATCH"
            portoneResponse.transactionId != request.transactionId -> "TX_ID_MISMATCH"
            else -> null
        }

        // 락 재검증은 PortOne HTTP 호출 이후 짧은 트랜잭션 안에서만 보유한다.
        val finalPayment = transactionTemplate.execute<Payment> {
            val locked = paymentRepository.findByIdForUpdate(payment.id!!).orElseThrow {
                PaymentException(ErrorCode.RESOURCE_NOT_FOUND)
            }
            when (locked.status) {
                PaymentStatus.SUCCESS -> throw PaymentException(ErrorCode.PAYMENT_ALREADY_EXISTS)
                PaymentStatus.FAILED, PaymentStatus.REFUNDED -> throw PaymentException(ErrorCode.PAYMENT_FAILED)
                PaymentStatus.PENDING -> Unit
            }
            if (failureReason == null) {
                val paidAt = portoneResponse.paidAt?.toLocalDateTime() ?: LocalDateTime.now(ZoneOffset.UTC)
                locked.markSuccess(portoneResponse.transactionId, responseJson, paidAt)
                recordPaymentSuccess(locked, reservation)
            } else {
                locked.markFailed(failureReason, responseJson)
                recordPaymentFailed(locked, failureReason)
            }
            paymentRepository.save(locked)
        } ?: throw PaymentException(ErrorCode.INTERNAL_SERVER_ERROR)

        log.info { "Payment confirmed: paymentId=${finalPayment.id}, status=${finalPayment.status}, failureReason=$failureReason" }

        return ConfirmResponse(
            paymentId = finalPayment.id!!,
            status = finalPayment.status,
            paidAt = finalPayment.paidAt
        )
    }

    /**
     * 결제 상세 조회 (REQ-PAY-014, Plan §3 Step 3, AC-1~4 & AC-11).
     *
     * 소유권 검증은 read context — UUIDv4 entropy 가 충분히 높아 IDOR 위험이 write 보다 낮으나
     * confirmPayment 와 동일하게 NOT_FOUND/FORBIDDEN 구분을 유지한다 (Plan §7 Decision 4).
     * `cardName`/`cardNumber` 는 [PaymentMaskingMapper] 가 PortOne 응답에서 read-time 으로 추출 —
     * status=PENDING/FAILED 또는 method 가 카드 아닌 경우 자연스럽게 null 이 된다.
     */
    @Transactional(readOnly = true)
    fun getPayment(userId: UUID, paymentId: UUID): DetailResponse {
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { PaymentException(ErrorCode.RESOURCE_NOT_FOUND) }
        if (payment.userId != userId) throw PaymentException(ErrorCode.FORBIDDEN)

        val cardMeta = paymentMaskingMapper.extract(payment.portoneResponse)
        return DetailResponse(
            paymentId = payment.id!!,
            reservationId = payment.reservationId,
            amount = payment.amount,
            status = payment.status,
            method = payment.paymentMethod,
            cardName = cardMeta.name,
            cardNumber = cardMeta.number,
        )
    }

    /**
     * 내 결제 내역 페이징 조회 (REQ-PAY-015, Plan §3 Step 3, AC-5/6/7/9).
     *
     * 정렬 기준 `createdAt DESC` 는 Service 내부에서 hard-code — 호출자가 sort 미주입할 가능성을 차단하고,
     * `idx_payments_user_created (user_id, created_at DESC)` 인덱스 hit 을 보장한다 (Plan §7 Decision 3).
     * 모든 status (PENDING/SUCCESS/FAILED/REFUNDED) 를 반환 — 필터 옵션은 후속 이슈 (Plan §7 Decision 9).
     * REFUNDED 결제는 markSuccess 단계의 `paidAt` 을 유지하므로 그대로 노출된다 (Critic N1 정정).
     */
    @Transactional(readOnly = true)
    fun listPayments(userId: UUID, page: Int, size: Int): PageResponse<ListItem> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val resultPage = paymentRepository.findByUserId(userId, pageable)
        val items = resultPage.content.map { payment ->
            ListItem(
                paymentId = payment.id!!,
                reservationId = payment.reservationId,
                amount = payment.amount,
                status = payment.status,
                method = payment.paymentMethod,
                paidAt = payment.paidAt,
            )
        }
        return PageResponse(
            list = items,
            page = page,
            size = size,
            totalElements = resultPage.totalElements,
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
