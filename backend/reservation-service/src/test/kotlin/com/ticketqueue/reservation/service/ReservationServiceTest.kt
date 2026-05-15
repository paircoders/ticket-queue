package com.ticketqueue.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.outbox.OutboxEvent
import com.ticketqueue.common.outbox.OutboxEventRepository
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
    private lateinit var outboxEventRepository: OutboxEventRepository
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
        outboxEventRepository = mockk()

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
            outboxEventRepository
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
        @DisplayName("payload를 ReservationCancelledEvent로 역직렬화할 수 있고 표준 envelope 필드를 포함한다")
        fun cancelledPayloadIsDeserializableWithCorrectFields() {
            val outboxSlot = slot<OutboxEvent>()
            setUpCancelSuccess(outboxSlot)

            val response = reservationService.cancelReservation(userId, reservationId)

            val event = objectMapper.readValue(outboxSlot.captured.payload, ReservationCancelledEvent::class.java)
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
            outboxSlot.captured.aggregateType shouldBe "Reservation"
            outboxSlot.captured.eventType shouldBe "ReservationCancelled"
            response.id shouldBe reservationId
            response.refundAmount shouldBe BigDecimal.ZERO
        }

        @Test
        @DisplayName("payload JSON에 reservationId, paymentId, cancelledAt 필드가 없다")
        fun cancelledPayloadDoesNotContainRemovedFields() {
            val outboxSlot = slot<OutboxEvent>()
            setUpCancelSuccess(outboxSlot)

            reservationService.cancelReservation(userId, reservationId)

            val tree = objectMapper.readTree(outboxSlot.captured.payload)
            tree.has("reservationId") shouldBe false
            tree.has("paymentId") shouldBe false
            tree.has("cancelledAt") shouldBe false
        }

        @Test
        @DisplayName("CONFIRMED 예매 취소 시 refundAmount는 totalAmount와 같다")
        fun confirmedCancellationHasRefundAmount() {
            val totalAmount = BigDecimal("150000")
            val outboxSlot = slot<OutboxEvent>()
            setUpCancelSuccess(outboxSlot, status = ReservationStatus.CONFIRMED, totalAmount = totalAmount)

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
            outboxSlot: io.mockk.CapturingSlot<OutboxEvent>,
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
            every { outboxEventRepository.save(capture(outboxSlot)) } answers { firstArg() }
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
}
