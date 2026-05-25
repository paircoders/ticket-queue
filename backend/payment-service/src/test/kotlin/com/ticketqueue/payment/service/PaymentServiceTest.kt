package com.ticketqueue.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneCircuitOpenException
import com.ticketqueue.common.external.portone.PortoneCustomer
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortonePaymentAmount
import com.ticketqueue.common.external.portone.PortonePaymentResponse
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneSelectedChannel
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.common.outbox.OutboxEvent
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.payment.client.ReservationServiceClient
import com.ticketqueue.payment.dto.PaymentDto.ConfirmRequest
import com.ticketqueue.payment.dto.PaymentDto.CreateRequest
import com.ticketqueue.payment.entity.Payment
import com.ticketqueue.payment.entity.PaymentStatus
import com.ticketqueue.payment.exception.PaymentException
import com.ticketqueue.payment.repository.PaymentRepository
import feign.FeignException
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.function.Consumer

@DisplayName("PaymentService 단위 테스트")
class PaymentServiceTest {

    private lateinit var paymentRepository: PaymentRepository
    private lateinit var reservationServiceClient: ReservationServiceClient
    private lateinit var portoneClient: PortoneFeignClient
    private lateinit var portoneTokenService: PortoneTokenService
    private lateinit var portoneProperties: PortoneProperties
    private lateinit var outboxEventRecorder: OutboxEventRecorder
    private lateinit var transactionTemplate: TransactionTemplate
    private val objectMapper: ObjectMapper = ObjectMapper().findAndRegisterModules()
    private lateinit var paymentMaskingMapper: PaymentMaskingMapper
    private lateinit var paymentService: PaymentService

    private val userId = UUID.randomUUID()
    private val reservationId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val amount = BigDecimal("300000")
    private val storeId = "test-store-id"
    private val channelKey = "test-channel-key"
    private val bearerToken = "Bearer test-token"
    private val seatIds = listOf(UUID.randomUUID(), UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        reservationServiceClient = mockk()
        portoneClient = mockk()
        portoneTokenService = mockk()
        portoneProperties = mockk()
        outboxEventRecorder = mockk()
        transactionTemplate = mockk()
        paymentMaskingMapper = mockk(relaxed = true)

        every { portoneProperties.storeId } returns storeId
        every { portoneProperties.channelKey } returns channelKey
        every { portoneTokenService.getAccessToken() } returns bearerToken
        every { paymentRepository.existsByReservationIdAndStatusIn(any(), any()) } returns false
        every { outboxEventRecorder.record(any()) } answers {
            OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = "Payment",
                aggregateId = UUID.randomUUID(),
                eventType = "stub",
                payload = "{}"
            )
        }
        // TransactionTemplate#executeWithoutResult(Consumer<TransactionStatus>): 람다 즉시 실행
        every { transactionTemplate.executeWithoutResult(any()) } answers {
            firstArg<Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
        }
        every { transactionTemplate.execute<Any?>(any()) } answers {
            val callback = firstArg<TransactionCallback<Any?>>()
            callback.doInTransaction(mockk(relaxed = true))
        }

        paymentService = PaymentService(
            paymentRepository,
            reservationServiceClient,
            portoneClient,
            portoneTokenService,
            portoneProperties,
            outboxEventRecorder,
            transactionTemplate,
            objectMapper,
            paymentMaskingMapper,
        )
    }

    private fun buildReservation(
        ownerId: UUID = userId,
        totalAmount: BigDecimal = amount,
        status: ReservationServiceClient.ReservationStatus = ReservationServiceClient.ReservationStatus.PENDING,
        holdExpiresAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
    ) = ReservationServiceClient.ReservationDetailResponse(
        reservationId = reservationId,
        userId = ownerId,
        scheduleId = scheduleId,
        totalAmount = totalAmount,
        status = status,
        holdExpiresAt = holdExpiresAt,
        seatIds = seatIds
    )

    private fun buildPayment(
        id: UUID = UUID.randomUUID(),
        ownerId: UUID = userId,
        paymentKey: String = "pk-abc",
        paymentAmount: BigDecimal = amount,
        status: PaymentStatus = PaymentStatus.PENDING,
    ): Payment {
        val payment = Payment(
            id = id,
            reservationId = reservationId,
            userId = ownerId,
            paymentKey = paymentKey,
            amount = paymentAmount,
        )
        when (status) {
            PaymentStatus.PENDING -> Unit
            PaymentStatus.SUCCESS -> payment.markSuccess("tx-x", "{}", LocalDateTime.now(ZoneOffset.UTC))
            PaymentStatus.FAILED -> payment.markFailed("preset")
            PaymentStatus.REFUNDED -> {
                payment.markSuccess("tx-x", "{}", LocalDateTime.now(ZoneOffset.UTC))
                payment.refund()
            }
        }
        return payment
    }

    private fun buildPortoneResponse(
        status: String = "PAID",
        totalAmount: Long = amount.longValueExact(),
        transactionId: String = "tx-1",
        paidAt: OffsetDateTime? = OffsetDateTime.now(ZoneOffset.UTC),
    ): PortonePaymentResponse = PortonePaymentResponse(
        id = "pk-abc",
        transactionId = transactionId,
        merchantId = "m",
        storeId = storeId,
        status = status,
        amount = PortonePaymentAmount(
            total = totalAmount,
            taxFree = 0,
            discount = 0,
            paid = totalAmount,
            cancelled = 0,
            cancelledTaxFree = 0,
        ),
        currency = "KRW",
        channel = PortoneSelectedChannel(type = "TEST", pgProvider = "stub", pgMerchantId = "m"),
        version = "v2",
        requestedAt = OffsetDateTime.now(ZoneOffset.UTC),
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC),
        statusChangedAt = OffsetDateTime.now(ZoneOffset.UTC),
        orderName = "order",
        customer = PortoneCustomer(),
        paidAt = paidAt,
    )

    @Nested
    @DisplayName("createPayment")
    inner class CreatePayment {

        @Test
        @DisplayName("정상 흐름: Payment가 PENDING 상태로 저장되고 CreateResponse를 반환한다 (Outbox 발행 없음)")
        fun success() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            justRun { portoneClient.preRegisterPayment(any(), any(), any()) }
            every { paymentRepository.save(any()) } answers {
                val p = firstArg<Payment>()
                Payment(
                    id = UUID.randomUUID(),
                    reservationId = p.reservationId,
                    userId = p.userId,
                    paymentKey = p.paymentKey,
                    amount = p.amount,
                    paymentMethod = p.paymentMethod
                )
            }

            val response = paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))

            response.amount shouldBe amount
            response.storeId shouldBe storeId
            response.channelKey shouldBe channelKey
            verify(exactly = 1) { portoneClient.preRegisterPayment(any(), any(), bearerToken) }
            verify(exactly = 1) { paymentRepository.save(any()) }
            // 정상 흐름에서는 Outbox 발행이 없어야 한다 (PaymentSuccess 는 #59 confirm 트리거)
            verify(exactly = 0) { outboxEventRecorder.record(any()) }
            verify(exactly = 0) { transactionTemplate.executeWithoutResult(any()) }
        }

        @Test
        @DisplayName("예매 소유자가 다르면 FORBIDDEN 예외가 발생한다")
        fun forbiddenWhenDifferentUser() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation(ownerId = UUID.randomUUID())

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.FORBIDDEN
        }

        @Test
        @DisplayName("요청 금액과 예매 금액이 다르면 PAYMENT_AMOUNT_MISMATCH 예외가 발생한다")
        fun amountMismatch() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation(totalAmount = BigDecimal("200000"))

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.PAYMENT_AMOUNT_MISMATCH
        }

        @Test
        @DisplayName("예매 상태가 PENDING이 아니면 RESERVATION_NOT_PAYABLE 예외가 발생한다")
        fun holdExpiredWhenNotPending() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation(status = ReservationServiceClient.ReservationStatus.CONFIRMED)

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.RESERVATION_NOT_PAYABLE
        }

        @Test
        @DisplayName("예매 서비스가 404를 반환하면 RESERVATION_NOT_FOUND 예외가 발생한다")
        fun reservationNotFound() {
            every { reservationServiceClient.getReservation(reservationId) } throws mockk<FeignException.NotFound>()

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.RESERVATION_NOT_FOUND
        }

        @Test
        @DisplayName("예매 서비스 네트워크 오류 시 INTERNAL_SERVER_ERROR 예외가 발생한다")
        fun reservationServiceUnavailable() {
            every { reservationServiceClient.getReservation(reservationId) } throws mockk<FeignException.ServiceUnavailable>()

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.INTERNAL_SERVER_ERROR
        }

        @Test
        @DisplayName("동일 reservationId에 PENDING 결제가 이미 존재하면 PAYMENT_ALREADY_EXISTS 예외가 발생한다")
        fun duplicatePayment() {
            every {
                paymentRepository.existsByReservationIdAndStatusIn(
                    reservationId, listOf(PaymentStatus.PENDING, PaymentStatus.SUCCESS)
                )
            } returns true

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.PAYMENT_ALREADY_EXISTS
            verify(exactly = 0) { reservationServiceClient.getReservation(any()) }
        }

        @Test
        @DisplayName("existsByReservationIdAndStatusIn이 false를 반환했으나 save()에서 DB 유니크 제약 위반 시 PAYMENT_ALREADY_EXISTS 예외가 발생한다")
        fun duplicatePaymentAtDbLevel() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { paymentRepository.save(any()) } throws DataIntegrityViolationException("unique constraint")

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.PAYMENT_ALREADY_EXISTS
            verify(exactly = 1) { reservationServiceClient.getReservation(reservationId) }
        }

        @Test
        @DisplayName("holdExpiresAt이 현재 시각보다 과거이면 HOLD_EXPIRED 예외가 발생한다")
        fun holdExpiredWhenTimeOver() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation(
                holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)
            )

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.HOLD_EXPIRED
        }

        @Test
        @DisplayName("holdExpiresAt이 현재 시각과 정확히 같으면 HOLD_EXPIRED 예외가 발생한다")
        fun holdExpiredAtExactBoundary() {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            every { reservationServiceClient.getReservation(reservationId) } returns
                buildReservation(holdExpiresAt = now)

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.HOLD_EXPIRED
        }

        @Test
        @DisplayName("PortOne pre-register 실패 시 Payment가 FAILED로 저장되고 PORTONE_PRE_REGISTER_FAILED 예외가 발생한다")
        fun portonePreRegisterFails() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { paymentRepository.save(any()) } answers {
                val p = firstArg<Payment>()
                Payment(
                    id = UUID.randomUUID(),
                    reservationId = p.reservationId,
                    userId = p.userId,
                    paymentKey = p.paymentKey,
                    amount = p.amount,
                    paymentMethod = p.paymentMethod
                )
            }
            every { portoneClient.preRegisterPayment(any(), any(), any()) } throws
                RuntimeException("PortOne connection failed")

            val ex = assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.PORTONE_PRE_REGISTER_FAILED
            // PENDING 저장 1회 + 트랜잭션 안에서 FAILED 저장 1회 = 총 2회
            verify(exactly = 2) { paymentRepository.save(any()) }
            verify(exactly = 1) { portoneClient.preRegisterPayment(any(), any(), any()) }
            verify(exactly = 1) { transactionTemplate.executeWithoutResult(any()) }
            verify(exactly = 1) { outboxEventRecorder.record(any<PaymentFailedEvent>()) }
        }

        @Test
        @DisplayName("PortOne pre-register 실패 시 PaymentFailedEvent envelope 필드가 정확히 채워진다")
        fun portonePreRegisterFailsRecordsPaymentFailedEnvelope() {
            val savedPaymentId = UUID.randomUUID()
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { paymentRepository.save(any()) } answers {
                val p = firstArg<Payment>()
                Payment(
                    id = savedPaymentId,
                    reservationId = p.reservationId,
                    userId = p.userId,
                    paymentKey = p.paymentKey,
                    amount = p.amount,
                    paymentMethod = p.paymentMethod
                )
            }
            val eventSlot = slot<PaymentFailedEvent>()
            every { outboxEventRecorder.record(capture(eventSlot)) } returns mockk<OutboxEvent>(relaxed = true)
            every { portoneClient.preRegisterPayment(any(), any(), any()) } throws
                RuntimeException("connection refused")

            assertThrows<PaymentException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            val event = eventSlot.captured
            event.aggregateType shouldBe "Payment"
            event.eventType shouldBe "PaymentFailed"
            event.aggregateId shouldBe savedPaymentId
            event.reservationId shouldBe reservationId
            event.reason shouldBe "PortOne pre-register failed: connection refused"
            event.metadata.userId shouldBe userId
        }

        @Test
        @DisplayName("PortOne CircuitBreaker Open(FallbackFactory) 시 markFailed + Outbox 발행 후 PortoneCircuitOpenException(503) 전파")
        fun portoneCircuitBreakerOpen() {
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { paymentRepository.save(any()) } answers {
                val p = firstArg<Payment>()
                Payment(
                    id = UUID.randomUUID(),
                    reservationId = p.reservationId,
                    userId = p.userId,
                    paymentKey = p.paymentKey,
                    amount = p.amount,
                    paymentMethod = p.paymentMethod
                )
            }
            val eventSlot = slot<PaymentFailedEvent>()
            every { outboxEventRecorder.record(capture(eventSlot)) } returns mockk<OutboxEvent>(relaxed = true)
            every { portoneClient.preRegisterPayment(any(), any(), any()) } throws PortoneCircuitOpenException()

            val ex = assertThrows<PortoneCircuitOpenException> {
                paymentService.createPayment(userId, CreateRequest(reservationId = reservationId, amount = amount))
            }

            ex.errorCode shouldBe ErrorCode.PORTONE_CIRCUIT_OPEN
            // PENDING 저장 1회 + 트랜잭션 안에서 FAILED 저장 1회 = 총 2회
            verify(exactly = 2) { paymentRepository.save(any()) }
            verify(exactly = 1) { transactionTemplate.executeWithoutResult(any()) }
            verify(exactly = 1) { outboxEventRecorder.record(any<PaymentFailedEvent>()) }
            eventSlot.captured.reason shouldBe "PortOne circuit breaker open"
        }
    }

    @Nested
    @DisplayName("confirmPayment")
    inner class ConfirmPayment {

        private val paymentId = UUID.randomUUID()
        private val paymentKey = "pk-abc"
        private val transactionId = "tx-1"

        private fun confirmRequest(
            reservationId: UUID = this@PaymentServiceTest.reservationId,
            paymentId: UUID = this.paymentId,
            paymentKey: String = this.paymentKey,
            transactionId: String = this.transactionId,
            amount: BigDecimal = this@PaymentServiceTest.amount,
        ) = ConfirmRequest(
            reservationId = reservationId,
            paymentId = paymentId,
            paymentKey = paymentKey,
            transactionId = transactionId,
            amount = amount,
        )

        @Test
        @DisplayName("정상 흐름: PortOne PAID 응답 → markSuccess + PaymentSuccessEvent 발행, ConfirmResponse 반환")
        fun success() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { paymentRepository.findByIdForUpdate(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { portoneClient.getPayment(paymentKey, storeId, bearerToken) } returns buildPortoneResponse(transactionId = transactionId)
            every { paymentRepository.save(any()) } answers { firstArg() }
            val captured = slot<PaymentSuccessEvent>()
            every { outboxEventRecorder.record(capture(captured)) } answers {
                OutboxEvent(id = UUID.randomUUID(), aggregateType = "Payment", aggregateId = paymentId, eventType = "PaymentSuccess", payload = "{}")
            }

            val response = paymentService.confirmPayment(userId, confirmRequest())

            response.paymentId shouldBe paymentId
            response.status shouldBe PaymentStatus.SUCCESS
            response.paidAt shouldBe payment.paidAt
            payment.status shouldBe PaymentStatus.SUCCESS
            payment.portoneTransactionId shouldBe transactionId
            captured.captured.reservationId shouldBe reservationId
            captured.captured.scheduleId shouldBe scheduleId
            captured.captured.seatIds shouldBe seatIds
            captured.captured.portoneTransactionId shouldBe transactionId
            verify(exactly = 1) { outboxEventRecorder.record(any<PaymentSuccessEvent>()) }
        }

        @Test
        @DisplayName("이미 SUCCESS인 결제 재호출 시 PAYMENT_ALREADY_EXISTS 예외 (멱등성)")
        fun idempotentSuccess() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey, status = PaymentStatus.SUCCESS)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.PAYMENT_ALREADY_EXISTS
            verify(exactly = 0) { portoneClient.getPayment(any(), any(), any()) }
        }

        @Test
        @DisplayName("이미 FAILED인 결제 재호출 시 PAYMENT_FAILED 예외")
        fun alreadyFailed() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey, status = PaymentStatus.FAILED)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.PAYMENT_FAILED
        }

        @Test
        @DisplayName("이미 REFUNDED인 결제 재호출 시 PAYMENT_FAILED 예외")
        fun alreadyRefunded() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey, status = PaymentStatus.REFUNDED)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.PAYMENT_FAILED
            verify(exactly = 0) { portoneClient.getPayment(any(), any(), any()) }
        }

        @Test
        @DisplayName("락 재조회 시 다른 트랜잭션이 SUCCESS로 선점한 경우 PAYMENT_ALREADY_EXISTS (race 차단)")
        fun raceWinnerCommittedSuccess() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey, status = PaymentStatus.PENDING)
            val racedSuccess = buildPayment(id = paymentId, paymentKey = paymentKey, status = PaymentStatus.SUCCESS)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { portoneClient.getPayment(paymentKey, storeId, bearerToken) } returns buildPortoneResponse()
            every { paymentRepository.findByIdForUpdate(paymentId) } returns Optional.of(racedSuccess)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.PAYMENT_ALREADY_EXISTS
            verify(exactly = 0) { outboxEventRecorder.record(any()) }
        }

        @Test
        @DisplayName("PortOne status가 PAID가 아니면 markFailed + PaymentFailedEvent 발행, status=FAILED 응답")
        fun portoneNotPaid() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { paymentRepository.findByIdForUpdate(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { portoneClient.getPayment(paymentKey, storeId, bearerToken) } returns buildPortoneResponse(status = "FAILED")
            every { paymentRepository.save(any()) } answers { firstArg() }
            val captured = slot<PaymentFailedEvent>()
            every { outboxEventRecorder.record(capture(captured)) } answers {
                OutboxEvent(id = UUID.randomUUID(), aggregateType = "Payment", aggregateId = paymentId, eventType = "PaymentFailed", payload = "{}")
            }

            val response = paymentService.confirmPayment(userId, confirmRequest())

            response.status shouldBe PaymentStatus.FAILED
            response.paidAt shouldBe null
            payment.status shouldBe PaymentStatus.FAILED
            payment.failureReason shouldBe "PORTONE_STATUS_FAILED"
            captured.captured.reason shouldBe "PORTONE_STATUS_FAILED"
            verify(exactly = 1) { outboxEventRecorder.record(any<PaymentFailedEvent>()) }
        }

        @Test
        @DisplayName("PortOne 금액이 DB 금액과 다르면 markFailed + reason=AMOUNT_MISMATCH")
        fun amountMismatch() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { paymentRepository.findByIdForUpdate(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { portoneClient.getPayment(paymentKey, storeId, bearerToken) } returns buildPortoneResponse(totalAmount = 100L)
            every { paymentRepository.save(any()) } answers { firstArg() }

            val response = paymentService.confirmPayment(userId, confirmRequest())

            response.status shouldBe PaymentStatus.FAILED
            payment.failureReason shouldBe "AMOUNT_MISMATCH"
        }

        @Test
        @DisplayName("PortOne transactionId가 요청과 다르면 markFailed + reason=TX_ID_MISMATCH")
        fun txIdMismatch() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { paymentRepository.findByIdForUpdate(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { portoneClient.getPayment(paymentKey, storeId, bearerToken) } returns buildPortoneResponse(transactionId = "tx-other")
            every { paymentRepository.save(any()) } answers { firstArg() }

            val response = paymentService.confirmPayment(userId, confirmRequest())

            response.status shouldBe PaymentStatus.FAILED
            payment.failureReason shouldBe "TX_ID_MISMATCH"
        }

        @Test
        @DisplayName("요청 본문 paymentKey가 DB와 다르면 INVALID_INPUT 예외 (위변조 차단)")
        fun paymentKeyMismatch() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest(paymentKey = "tampered"))
            }

            ex.errorCode shouldBe ErrorCode.INVALID_INPUT
        }

        @Test
        @DisplayName("요청 본문 reservationId가 DB와 다르면 INVALID_INPUT 예외")
        fun reservationIdMismatch() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest(reservationId = UUID.randomUUID()))
            }

            ex.errorCode shouldBe ErrorCode.INVALID_INPUT
        }

        @Test
        @DisplayName("요청 본문 amount가 DB와 다르면 INVALID_INPUT 예외")
        fun requestAmountMismatch() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest(amount = BigDecimal("999")))
            }

            ex.errorCode shouldBe ErrorCode.INVALID_INPUT
        }

        @Test
        @DisplayName("다른 사용자의 결제 → FORBIDDEN")
        fun forbidden() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey, ownerId = UUID.randomUUID())
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.FORBIDDEN
        }

        @Test
        @DisplayName("Payment 부재 → RESOURCE_NOT_FOUND")
        fun paymentNotFound() {
            every { paymentRepository.findById(paymentId) } returns Optional.empty()

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
        }

        @Test
        @DisplayName("Reservation holdExpiresAt이 만료되면 HOLD_EXPIRED 예외")
        fun holdExpired() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns
                buildReservation(holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.HOLD_EXPIRED
            verify(exactly = 0) { portoneClient.getPayment(any(), any(), any()) }
        }

        @Test
        @DisplayName("PortOne 호출이 FeignException으로 실패하면 PORTONE_API_ERROR 예외")
        fun portoneApiError() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { portoneClient.getPayment(any(), any(), any()) } throws mockk<FeignException.ServiceUnavailable>()

            val ex = assertThrows<PaymentException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.PORTONE_API_ERROR
            verify(exactly = 0) { outboxEventRecorder.record(any()) }
        }

        @Test
        @DisplayName("PortOne CircuitBreaker Open(FallbackFactory) 시 Payment는 PENDING 유지, PortoneCircuitOpenException(503) 전파")
        fun portoneCircuitBreakerOpen() {
            val payment = buildPayment(id = paymentId, paymentKey = paymentKey)
            every { paymentRepository.findById(paymentId) } returns Optional.of(payment)
            every { reservationServiceClient.getReservation(reservationId) } returns buildReservation()
            every { portoneClient.getPayment(any(), any(), any()) } throws PortoneCircuitOpenException()

            val ex = assertThrows<PortoneCircuitOpenException> {
                paymentService.confirmPayment(userId, confirmRequest())
            }

            ex.errorCode shouldBe ErrorCode.PORTONE_CIRCUIT_OPEN
            // CB Open 분기는 트랜잭션 진입 전이므로 락 재조회/Outbox 모두 호출되지 않아야 한다.
            verify(exactly = 0) { paymentRepository.findByIdForUpdate(any()) }
            verify(exactly = 0) { paymentRepository.save(any()) }
            verify(exactly = 0) { outboxEventRecorder.record(any()) }
            payment.status shouldBe PaymentStatus.PENDING
        }
    }
}
