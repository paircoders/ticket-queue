package com.ticketqueue.payment.service

import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.common.outbox.OutboxEvent
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.payment.client.ReservationServiceClient
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
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
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
    private lateinit var paymentService: PaymentService

    private val userId = UUID.randomUUID()
    private val reservationId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val amount = BigDecimal("300000")
    private val storeId = "test-store-id"
    private val channelKey = "test-channel-key"
    private val bearerToken = "Bearer test-token"

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        reservationServiceClient = mockk()
        portoneClient = mockk()
        portoneTokenService = mockk()
        portoneProperties = mockk()
        outboxEventRecorder = mockk()
        transactionTemplate = mockk()

        every { portoneProperties.storeId } returns storeId
        every { portoneProperties.channelKey } returns channelKey
        every { portoneTokenService.getAccessToken() } returns bearerToken
        every { paymentRepository.existsByReservationIdAndStatusIn(any(), any()) } returns false
        // TransactionTemplate stub: 람다를 즉시 실행하여 트랜잭션 통과를 시뮬레이션
        every { transactionTemplate.executeWithoutResult(any()) } answers {
            firstArg<Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
        }

        paymentService = PaymentService(
            paymentRepository,
            reservationServiceClient,
            portoneClient,
            portoneTokenService,
            portoneProperties,
            outboxEventRecorder,
            transactionTemplate,
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
        seatIds = listOf(UUID.randomUUID())
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
            every { outboxEventRecorder.record(any()) } returns mockk<OutboxEvent>(relaxed = true)
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
    }
}
