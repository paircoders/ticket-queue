package com.ticketqueue.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.event.EventMetadata
import com.ticketqueue.common.event.PaymentFailedEvent
import com.ticketqueue.common.event.PaymentSuccessEvent
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.dto.ReservationDto.HoldRequest
import com.ticketqueue.reservation.dto.ReservationDto.SeatStatusResponse
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationSeat
import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.exception.ReservationException
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.function.Consumer
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

@DisplayName("ReservationService 단위 테스트")
class ReservationServiceTest {

    private lateinit var stringRedisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var setOps: SetOperations<String, String>
    private lateinit var redissonClient: RedissonClient
    private lateinit var userLock: RLock
    private lateinit var seatLock: RLock
    private lateinit var multiLock: RLock
    private lateinit var eventServiceClient: EventServiceClient
    private lateinit var reservationRepository: ReservationRepository
    private lateinit var reservationSeatRepository: ReservationSeatRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var outboxEventRecorder: OutboxEventRecorder
    private lateinit var reservationService: ReservationService

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val seatId1 = UUID.randomUUID()
    private val validToken = "valid-queue-token"
    private val tokenJson get() = """{"userId":"$userId","scheduleId":"$scheduleId","issuedAt":"1234567890"}"""

    @BeforeEach
    fun setUp() {
        stringRedisTemplate = mockk()
        valueOps = mockk()
        setOps = mockk()
        redissonClient = mockk()
        userLock = mockk()
        seatLock = mockk()
        multiLock = mockk()
        eventServiceClient = mockk()
        reservationRepository = mockk()
        reservationSeatRepository = mockk()
        objectMapper = jacksonObjectMapper().apply { registerModule(JavaTimeModule()) }
        transactionTemplate = mockk()
        outboxEventRecorder = mockk()

        every { stringRedisTemplate.opsForValue() } returns valueOps
        every { stringRedisTemplate.opsForSet() } returns setOps

        reservationService = ReservationService(
            stringRedisTemplate,
            redissonClient,
            eventServiceClient,
            reservationRepository,
            reservationSeatRepository,
            objectMapper,
            transactionTemplate,
            outboxEventRecorder
        )
    }

    @Nested
    @DisplayName("QueueToken 검증")
    inner class QueueTokenValidation {
        @Test
        @DisplayName("Redis에 토큰이 없으면 QUEUE_TOKEN_EXPIRED 예외를 던진다")
        fun throwsWhenTokenNotFound() {
            every { valueOps.get("queue:token:$validToken") } returns null
            val ex = assertThrows<ReservationException> {
                reservationService.holdSeats(userId, holdRequest(), validToken)
            }
            ex.errorCode shouldBe ErrorCode.QUEUE_TOKEN_EXPIRED
        }
    }

    @Nested
    @DisplayName("최대 좌석 수 검증")
    inner class SeatCountValidation {
        @Test
        @DisplayName("기존 PENDING 예매 좌석 + 신규 요청이 4개를 초과하면 MAX_SEATS_EXCEEDED 예외를 던진다")
        fun throwsWhenExceedsMaxSeats() {
            val seatId2 = UUID.randomUUID()
            setUpTokenValid()
            setUpLockObjects(listOf(seatId1, seatId2))
            setUpUserLockSuccess()
            setUpMultiLockSuccess()
            val existingReservationId = UUID.randomUUID()
            val existingReservation = mockk<Reservation> { every { id } returns existingReservationId }
            every {
                reservationRepository.findByUserIdAndScheduleIdAndStatus(userId, scheduleId, ReservationStatus.PENDING)
            } returns listOf(existingReservation)
            every { reservationSeatRepository.countByReservationIdIn(listOf(existingReservationId)) } returns 3

            val ex = assertThrows<ReservationException> {
                reservationService.holdSeats(userId, HoldRequest(scheduleId, listOf(seatId1, seatId2)), validToken)
            }
            ex.errorCode shouldBe ErrorCode.MAX_SEATS_EXCEEDED
            verify { multiLock.unlock() }
            verify { userLock.unlock() }
        }
    }

    @Nested
    @DisplayName("분산 락 및 좌석 상태 검증")
    inner class LockAndSeatValidation {
        @Test
        @DisplayName("사용자 락 획득 실패 시 RESERVATION_IN_PROGRESS 예외를 던진다")
        fun throwsWhenUserLockFails() {
            setUpTokenValid()
            setUpLockObjects()
            every { userLock.tryLock(0, any(), any()) } returns false

            val ex = assertThrows<ReservationException> {
                reservationService.holdSeats(userId, holdRequest(), validToken)
            }
            ex.errorCode shouldBe ErrorCode.RESERVATION_IN_PROGRESS
        }

        @Test
        @DisplayName("MultiLock 획득 실패 시 SEAT_ALREADY_HELD 예외를 던지고 사용자 락을 해제한다")
        fun throwsWhenMultiLockFails() {
            setUpTokenValid()
            setUpLockObjects()
            setUpUserLockSuccess()
            every { multiLock.tryLock(0, any(), any()) } returns false

            val ex = assertThrows<ReservationException> {
                reservationService.holdSeats(userId, holdRequest(), validToken)
            }
            ex.errorCode shouldBe ErrorCode.SEAT_ALREADY_HELD
            verify { userLock.unlock() }
        }
    }

    @Nested
    @DisplayName("좌석 선점 성공")
    inner class HoldSuccess {
        @Test
        @DisplayName("정상 선점 시 HoldResponse를 반환하고 모든 락을 해제한다")
        fun returnsHoldResponse() {
            setUpTokenValid()
            setUpLockObjects()
            setUpUserLockSuccess()
            setUpMultiLockSuccess()
            setUpNoPendingReservation()
            every { stringRedisTemplate.execute(any<RedisCallback<*>>()) } returns listOf(false)
            setUpSoldAndDetails()
            setUpSaveSuccess()

            val response = reservationService.holdSeats(userId, holdRequest(), validToken)

            response.status shouldBe ReservationStatus.PENDING
            verify { multiLock.unlock() }
            verify { userLock.unlock() }
            verify { stringRedisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg()) }
        }

        @Test
        @DisplayName("트랜잭션 롤백 시 Redis hold_seats SET을 갱신하지 않는다")
        fun doesNotUpdateRedisOnRollback() {
            setUpTokenValid()
            setUpLockObjects()
            setUpUserLockSuccess()
            setUpMultiLockSuccess()
            setUpNoPendingReservation()
            every { stringRedisTemplate.execute(any<RedisCallback<*>>()) } returns listOf(false)
            setUpSoldAndDetails()

            // 트랜잭션 콜백 실행 중 예외 발생 — afterCommit 호출 없이 롤백
            every { transactionTemplate.execute<Reservation>(any()) } answers {
                TransactionSynchronizationManager.initSynchronization()
                try {
                    firstArg<TransactionCallback<Reservation>>().doInTransaction(
                        mockk<TransactionStatus>(relaxed = true)
                    )
                    throw RuntimeException("forced rollback")
                } finally {
                    TransactionSynchronizationManager.clearSynchronization()
                }
            }
            every { reservationRepository.save(any()) } returns mockk(relaxed = true)
            every { reservationSeatRepository.saveAll(any<List<ReservationSeat>>()) } returns emptyList()

            assertThrows<RuntimeException> {
                reservationService.holdSeats(userId, holdRequest(), validToken)
            }

            verify(exactly = 0) { stringRedisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg()) }
        }
    }

    @Nested
    @DisplayName("좌석 상태 조회")
    inner class SeatStatusQuery {

        @Test
        @DisplayName("Queue Token이 없으면 QUEUE_TOKEN_EXPIRED 예외를 던진다")
        fun throwsWhenTokenExpired() {
            every { valueOps.get("queue:token:$validToken") } returns null
            val ex = assertThrows<ReservationException> {
                reservationService.getSeatStatus(userId, scheduleId, validToken)
            }
            ex.errorCode shouldBe ErrorCode.QUEUE_TOKEN_EXPIRED
        }

        @Test
        @DisplayName("SOLD와 HOLD 좌석을 합산하여 available을 정확히 계산한다")
        fun returnsCorrectSeatStatus() {
            val soldId1 = UUID.randomUUID()
            val soldId2 = UUID.randomUUID()
            val holdId1 = UUID.randomUUID()
            setUpTokenValid()
            every { eventServiceClient.getSoldSeats(scheduleId) } returns
                EventServiceClient.SoldSeatsResponse(
                    scheduleId = scheduleId,
                    soldSeatIds = listOf(soldId1, soldId2),
                    totalSeats = 100
                )
            every { stringRedisTemplate.opsForSet().members("hold_seats:$scheduleId") } returns setOf(holdId1.toString())

            val result = reservationService.getSeatStatus(userId, scheduleId, validToken)

            result.seats.total shouldBe 100
            result.seats.sold shouldBe 2
            result.seats.hold shouldBe 1
            result.seats.available shouldBe 97
            result.sold shouldBe listOf(soldId1, soldId2)
            result.hold shouldBe listOf(holdId1)
        }

        @Test
        @DisplayName("hold_seats SET이 Redis에 없으면 hold는 빈 리스트로 반환한다")
        fun returnsEmptyHoldWhenSetAbsent() {
            setUpTokenValid()
            every { eventServiceClient.getSoldSeats(scheduleId) } returns
                EventServiceClient.SoldSeatsResponse(
                    scheduleId = scheduleId,
                    soldSeatIds = emptyList(),
                    totalSeats = 50
                )
            every { stringRedisTemplate.opsForSet().members("hold_seats:$scheduleId") } returns null

            val result = reservationService.getSeatStatus(userId, scheduleId, validToken)

            result.seats.total shouldBe 50
            result.seats.sold shouldBe 0
            result.seats.hold shouldBe 0
            result.seats.available shouldBe 50
            result.hold shouldBe emptyList<UUID>()
        }
    }

    @Nested
    @DisplayName("예매 취소")
    inner class CancelReservation {

        private val reservationId = UUID.randomUUID()

        @Test
        @DisplayName("OutboxEventRecorder.record() 호출 시 표준 envelope 필드를 포함한다")
        fun cancelledEventHasCorrectFields() {
            val eventSlot = slot<ReservationCancelledEvent>()
            setUpCancelSuccess(eventSlot)

            val response = reservationService.cancelReservation(userId, reservationId)

            val event = eventSlot.captured
            event.eventType shouldBe "ReservationCancelled"
            event.aggregateType shouldBe "Reservation"
            event.aggregateId shouldBe reservationId
            event.userId shouldBe userId
            event.scheduleId shouldBe scheduleId
            event.seatIds shouldBe listOf(seatId1)
            event.reason shouldBe "USER_REQUEST"
            event.metadata.userId shouldBe userId
            event.metadata.correlationId shouldNotBe null
            event.metadata.causationId shouldBe null
            response.id shouldBe reservationId
            response.refundAmount shouldBe BigDecimal.ZERO
        }

        @Test
        @DisplayName("이벤트 JSON에 reservationId, paymentId, cancelledAt 필드가 없다")
        fun cancelledEventDoesNotContainRemovedFields() {
            val eventSlot = slot<ReservationCancelledEvent>()
            setUpCancelSuccess(eventSlot)

            reservationService.cancelReservation(userId, reservationId)

            val tree = objectMapper.readTree(objectMapper.writeValueAsString(eventSlot.captured))
            tree.has("reservationId") shouldBe false
            tree.has("paymentId") shouldBe false
            tree.has("cancelledAt") shouldBe false
        }

        @Test
        @DisplayName("CONFIRMED 예매 취소 시 refundAmount는 totalAmount와 같다")
        fun confirmedCancellationHasRefundAmount() {
            val totalAmount = BigDecimal("150000")
            val eventSlot = slot<ReservationCancelledEvent>()
            setUpCancelSuccess(eventSlot, status = ReservationStatus.CONFIRMED, totalAmount = totalAmount)

            val response = reservationService.cancelReservation(userId, reservationId)

            response.id shouldBe reservationId
            response.refundAmount shouldBe totalAmount
        }

        @Test
        @DisplayName("예매를 찾을 수 없으면 RESERVATION_NOT_FOUND 예외를 던진다")
        fun throwsWhenNotFound() {
            every { reservationRepository.findByIdAndUserId(reservationId, userId) } returns null

            val ex = assertThrows<ReservationException> {
                reservationService.cancelReservation(userId, reservationId)
            }
            ex.errorCode shouldBe ErrorCode.RESERVATION_NOT_FOUND
        }

        @Test
        @DisplayName("이미 취소된 예매는 RESERVATION_ALREADY_CANCELLED 예외를 던진다")
        fun throwsWhenAlreadyCancelled() {
            val reservation = mockk<Reservation> {
                every { status } returns ReservationStatus.CANCELLED
            }
            every { reservationRepository.findByIdAndUserId(reservationId, userId) } returns reservation

            val ex = assertThrows<ReservationException> {
                reservationService.cancelReservation(userId, reservationId)
            }
            ex.errorCode shouldBe ErrorCode.RESERVATION_ALREADY_CANCELLED
        }

        @Test
        @DisplayName("공연 당일에는 CANCELLATION_NOT_ALLOWED 예외를 던진다")
        fun throwsWhenCancelledOnEventDay() {
            val reservation = mockk<Reservation>()
            every { reservation.status } returns ReservationStatus.PENDING
            every { reservation.scheduleId } returns scheduleId
            every { reservationRepository.findByIdAndUserId(reservationId, userId) } returns reservation
            every { eventServiceClient.getScheduleInfo(scheduleId) } returns eventScheduleToday()

            val ex = assertThrows<ReservationException> {
                reservationService.cancelReservation(userId, reservationId)
            }
            ex.errorCode shouldBe ErrorCode.CANCELLATION_NOT_ALLOWED
        }

        private fun setUpCancelSuccess(
            eventSlot: io.mockk.CapturingSlot<ReservationCancelledEvent>,
            status: ReservationStatus = ReservationStatus.PENDING,
            totalAmount: BigDecimal = BigDecimal.ZERO
        ) {
            val reservation = mockk<Reservation> {
                every { id } returns reservationId
                every { userId } returns this@ReservationServiceTest.userId
                every { scheduleId } returns this@ReservationServiceTest.scheduleId
                every { this@mockk.status } returns status
                every { this@mockk.totalAmount } returns totalAmount
                justRun { cancel() }
            }
            every { reservationRepository.findByIdAndUserId(reservationId, userId) } returns reservation
            every { eventServiceClient.getScheduleInfo(scheduleId) } returns eventScheduleTomorrow()
            every { reservationSeatRepository.findByReservationId(reservationId) } returns
                listOf(mockk { every { seatId } returns seatId1 })
            every { outboxEventRecorder.record(capture(eventSlot)) } returns mockk(relaxed = true)
            every { transactionTemplate.executeWithoutResult(any()) } answers {
                TransactionSynchronizationManager.initSynchronization()
                try {
                    firstArg<Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
                    TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
                } finally {
                    TransactionSynchronizationManager.clearSynchronization()
                }
            }
            every { setOps.remove(any<String>(), *anyVararg()) } returns 1L
        }

        private fun eventScheduleTomorrow() = EventServiceClient.ScheduleInfoResponse(
            scheduleId = scheduleId,
            eventId = eventId,
            eventStartAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
            eventEndAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(1).plusHours(3),
            saleStartAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(7),
            saleEndAt = LocalDateTime.now(ZoneOffset.UTC).plusDays(1)
        )

        private fun eventScheduleToday() = EventServiceClient.ScheduleInfoResponse(
            scheduleId = scheduleId,
            eventId = eventId,
            eventStartAt = LocalDateTime.now(ZoneOffset.UTC),
            eventEndAt = LocalDateTime.now(ZoneOffset.UTC).plusHours(3),
            saleStartAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(7),
            saleEndAt = LocalDateTime.now(ZoneOffset.UTC)
        )
    }

    private fun holdRequest() = HoldRequest(scheduleId, listOf(seatId1))

    private fun setUpTokenValid() {
        every { valueOps.get("queue:token:$validToken") } returns tokenJson
    }

    /**
     * getLock 이 키에 따라 다른 mock 객체를 반환하도록 정확한 키로 등록한다.
     * - "user:hold:lock:..." → userLock
     * - "seat:hold:..."      → seatLock
     * - getMultiLock(...)    → multiLock
     *
     * seatIds 를 명시하면 해당 좌석 키들을 모두 등록한다 (기본값: [seatId1]).
     */
    private fun setUpLockObjects(seatIds: List<UUID> = listOf(seatId1)) {
        every { redissonClient.getLock("user:hold:lock:$userId:$scheduleId") } returns userLock
        seatIds.forEach { id ->
            every { redissonClient.getLock("seat:hold:$scheduleId:$id") } returns seatLock
        }
        every { redissonClient.getMultiLock(*varargAny { true }) } returns multiLock
    }

    private fun setUpUserLockSuccess() {
        every { userLock.tryLock(0, any(), TimeUnit.SECONDS) } returns true
        every { userLock.isHeldByCurrentThread } returns true
        justRun { userLock.unlock() }
        every { userLock.name } returns "userLock"
    }

    private fun setUpMultiLockSuccess() {
        every { multiLock.tryLock(0, any(), TimeUnit.SECONDS) } returns true
        every { multiLock.isHeldByCurrentThread } returns true
        justRun { multiLock.unlock() }
        every { multiLock.name } returns "multiLock"
    }

    private fun setUpNoPendingReservation() {
        every {
            reservationRepository.findByUserIdAndScheduleIdAndStatus(userId, scheduleId, ReservationStatus.PENDING)
        } returns emptyList()
    }

    private fun setUpSoldAndDetails() {
        every { eventServiceClient.getSeatDetails(scheduleId, any()) } returns
            EventServiceClient.SeatDetailsResponse(
                scheduleId = scheduleId,
                eventId = eventId,
                seats = listOf(
                    EventServiceClient.SeatDetailsResponse.SeatDetail(
                        seatId = seatId1,
                        seatNumber = "A-1",
                        grade = "VIP",
                        price = BigDecimal("300000")
                    )
                )
            )
    }

    private fun setUpSaveSuccess() {
        val savedReservation = Reservation(
            id = UUID.randomUUID(),
            userId = userId,
            scheduleId = scheduleId,
            eventId = eventId,
            totalAmount = BigDecimal("300000"),
            holdExpiresAt = LocalDateTime.now().plusMinutes(5)
        )
        every { stringRedisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), *anyVararg()) } returns 1L
        every { transactionTemplate.execute<Reservation>(any()) } answers {
            TransactionSynchronizationManager.initSynchronization()
            try {
                val result = firstArg<TransactionCallback<Reservation>>().doInTransaction(mockk<TransactionStatus>(relaxed = true))
                TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
                result
            } finally {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }
        every { reservationRepository.save(any()) } returns savedReservation
        every { reservationSeatRepository.saveAll(any<List<ReservationSeat>>()) } returns emptyList()
    }

    @Nested
    @DisplayName("PaymentSuccess 수신 시 예매 확정")
    inner class ConfirmFromPaymentSuccess {

        private val reservationId = UUID.randomUUID()
        private val paymentId = UUID.randomUUID()
        private val seatId = UUID.randomUUID()

        @Test
        @DisplayName("PENDING 예매가 CONFIRMED 로 전이되고 ticketNumber 가 발급된다")
        fun confirmsPendingReservation() {
            val ticketSlot = slot<String>()
            val reservation = mockk<Reservation>(relaxed = true) {
                every { id } returns reservationId
                every { status } returns ReservationStatus.PENDING
            }
            every { reservationRepository.findById(reservationId) } returns java.util.Optional.of(reservation)
            stubExecuteWithoutResult()

            reservationService.confirmFromPaymentSuccess(paymentSuccessEvent())

            verify { reservation.confirm(paymentId, capture(ticketSlot)) }
            assert(ticketSlot.captured.matches(Regex("""^TKT-\d{8}-[A-Z0-9]{8}$"""))) {
                "ticketNumber must match TKT-yyyyMMdd-XXXXXXXX, got ${ticketSlot.captured}"
            }
        }

        @Test
        @DisplayName("이미 CONFIRMED 상태면 멱등 처리하여 no-op 한다")
        fun isIdempotentWhenAlreadyConfirmed() {
            val reservation = mockk<Reservation>(relaxed = true) {
                every { id } returns reservationId
                every { status } returns ReservationStatus.CONFIRMED
            }
            every { reservationRepository.findById(reservationId) } returns java.util.Optional.of(reservation)
            stubExecuteWithoutResult()

            reservationService.confirmFromPaymentSuccess(paymentSuccessEvent())

            verify(exactly = 0) { reservation.confirm(any(), any()) }
        }

        @Test
        @DisplayName("CANCELLED 상태면 RESERVATION_NOT_CHANGEABLE 예외를 던진다")
        fun rejectsCancelledReservation() {
            val reservation = mockk<Reservation>(relaxed = true) {
                every { id } returns reservationId
                every { status } returns ReservationStatus.CANCELLED
            }
            every { reservationRepository.findById(reservationId) } returns java.util.Optional.of(reservation)
            stubExecuteWithoutResult()

            val ex = assertThrows<ReservationException> {
                reservationService.confirmFromPaymentSuccess(paymentSuccessEvent())
            }
            ex.errorCode shouldBe ErrorCode.RESERVATION_NOT_CHANGEABLE
        }

        @Test
        @DisplayName("예매를 찾지 못하면 RESERVATION_NOT_FOUND 예외를 던진다")
        fun throwsWhenReservationMissing() {
            every { reservationRepository.findById(reservationId) } returns java.util.Optional.empty()
            stubExecuteWithoutResult()

            val ex = assertThrows<ReservationException> {
                reservationService.confirmFromPaymentSuccess(paymentSuccessEvent())
            }
            ex.errorCode shouldBe ErrorCode.RESERVATION_NOT_FOUND
        }

        private fun paymentSuccessEvent() = PaymentSuccessEvent(
            aggregateId = paymentId,
            reservationId = reservationId,
            paymentKey = "pk_test_123",
            amount = BigDecimal("300000"),
            paidAt = LocalDateTime.now(ZoneOffset.UTC),
            scheduleId = scheduleId,
            seatIds = listOf(seatId),
            portoneTransactionId = "portone-tx-1",
            metadata = EventMetadata(userId = userId)
        )
    }

    @Nested
    @DisplayName("PaymentFailed 수신 시 예매 취소 + ReservationCancelled outbox 발행")
    inner class CancelFromPaymentFailure {

        private val reservationId = UUID.randomUUID()
        private val paymentId = UUID.randomUUID()
        private val paymentEventId = UUID.randomUUID()
        private val correlationId = UUID.randomUUID()
        private val seatId = UUID.randomUUID()

        @Test
        @DisplayName("PENDING 예매를 CANCELLED 로 전이하고 ReservationCancelled outbox 를 PAYMENT_FAILED 사유로 발행한다")
        fun cancelsPendingReservationAndPublishesEvent() {
            val outboxSlot = slot<ReservationCancelledEvent>()
            val reservation = mockk<Reservation>(relaxed = true) {
                every { id } returns reservationId
                every { userId } returns this@ReservationServiceTest.userId
                every { scheduleId } returns this@ReservationServiceTest.scheduleId
                every { status } returns ReservationStatus.PENDING
            }
            every { reservationRepository.findById(reservationId) } returns java.util.Optional.of(reservation)
            every { reservationSeatRepository.findByReservationId(reservationId) } returns
                listOf(mockk { every { seatId } returns this@CancelFromPaymentFailure.seatId })
            every { outboxEventRecorder.record(capture(outboxSlot)) } returns mockk(relaxed = true)
            every { setOps.remove(any<String>(), *anyVararg()) } returns 1L
            stubExecuteWithoutResultWithAfterCommit()

            reservationService.cancelFromPaymentFailure(paymentFailedEvent())

            verify { reservation.cancel() }
            outboxSlot.captured.reason shouldBe "PAYMENT_FAILED"
            outboxSlot.captured.scheduleId shouldBe scheduleId
            outboxSlot.captured.seatIds shouldBe listOf(seatId)
            outboxSlot.captured.metadata.causationId shouldBe paymentEventId
            outboxSlot.captured.metadata.correlationId shouldBe correlationId
            verify { setOps.remove(any<String>(), *anyVararg()) }
        }

        @Test
        @DisplayName("이미 CANCELLED 상태면 멱등 처리하여 outbox 발행을 하지 않는다")
        fun isIdempotentWhenAlreadyCancelled() {
            val reservation = mockk<Reservation>(relaxed = true) {
                every { id } returns reservationId
                every { status } returns ReservationStatus.CANCELLED
            }
            every { reservationRepository.findById(reservationId) } returns java.util.Optional.of(reservation)
            stubExecuteWithoutResultWithAfterCommit()

            reservationService.cancelFromPaymentFailure(paymentFailedEvent())

            verify(exactly = 0) { outboxEventRecorder.record(any()) }
            verify(exactly = 0) { reservation.cancel() }
        }

        @Test
        @DisplayName("CONFIRMED 상태면 RESERVATION_NOT_CHANGEABLE 예외를 던진다")
        fun rejectsConfirmedReservation() {
            val reservation = mockk<Reservation>(relaxed = true) {
                every { id } returns reservationId
                every { status } returns ReservationStatus.CONFIRMED
            }
            every { reservationRepository.findById(reservationId) } returns java.util.Optional.of(reservation)
            stubExecuteWithoutResultWithAfterCommit()

            val ex = assertThrows<ReservationException> {
                reservationService.cancelFromPaymentFailure(paymentFailedEvent())
            }
            ex.errorCode shouldBe ErrorCode.RESERVATION_NOT_CHANGEABLE
        }

        private fun paymentFailedEvent() = PaymentFailedEvent(
            eventId = paymentEventId,
            aggregateId = paymentId,
            reservationId = reservationId,
            reason = "INSUFFICIENT_BALANCE",
            metadata = EventMetadata(correlationId = correlationId, causationId = null, userId = userId)
        )
    }

    private fun stubExecuteWithoutResult() {
        every { transactionTemplate.executeWithoutResult(any()) } answers {
            firstArg<Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
        }
    }

    private fun stubExecuteWithoutResultWithAfterCommit() {
        every { transactionTemplate.executeWithoutResult(any()) } answers {
            TransactionSynchronizationManager.initSynchronization()
            try {
                firstArg<Consumer<TransactionStatus>>().accept(mockk(relaxed = true))
                TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            } finally {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }
    }
}
