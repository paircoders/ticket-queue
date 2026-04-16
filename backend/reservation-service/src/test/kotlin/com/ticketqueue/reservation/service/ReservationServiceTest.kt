package com.ticketqueue.reservation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.reservation.client.EventServiceClient
import com.ticketqueue.reservation.dto.ReservationDto.HoldRequest
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationSeat
import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.exception.ReservationException
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
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
        objectMapper = jacksonObjectMapper()
        transactionTemplate = mockk()

        every { stringRedisTemplate.opsForValue() } returns valueOps
        every { stringRedisTemplate.opsForSet() } returns setOps

        reservationService = ReservationService(
            stringRedisTemplate,
            redissonClient,
            eventServiceClient,
            reservationRepository,
            reservationSeatRepository,
            objectMapper,
            transactionTemplate
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
            every { setOps.isMember(any(), any()) } returns false
            setUpSoldAndDetails()
            setUpSaveSuccess()

            val response = reservationService.holdSeats(userId, holdRequest(), validToken)

            response.status shouldBe ReservationStatus.PENDING
            verify { multiLock.unlock() }
            verify { userLock.unlock() }
        }
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
        // afterCommit 콜백이 TransactionSynchronizationManager를 사용하므로 동기화 컨텍스트를 활성화한다.
        // 단위 테스트에서는 afterCommit 콜백 실행 없이 컨텍스트만 초기화하여 예외를 방지한다.
        every { transactionTemplate.execute<Reservation>(any()) } answers {
            TransactionSynchronizationManager.initSynchronization()
            try {
                firstArg<TransactionCallback<Reservation>>().doInTransaction(mockk<TransactionStatus>(relaxed = true))
            } finally {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }
        every { reservationRepository.save(any()) } returns savedReservation
        every { reservationSeatRepository.saveAll(any<List<ReservationSeat>>()) } returns emptyList()
    }
}
