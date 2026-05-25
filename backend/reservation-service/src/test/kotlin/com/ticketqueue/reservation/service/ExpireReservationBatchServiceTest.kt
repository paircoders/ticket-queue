package com.ticketqueue.reservation.service

import com.ticketqueue.common.event.ReservationCancelledEvent
import com.ticketqueue.common.outbox.OutboxEventRecorder
import com.ticketqueue.reservation.entity.Reservation
import com.ticketqueue.reservation.entity.ReservationSeat
import com.ticketqueue.reservation.entity.ReservationStatus
import com.ticketqueue.reservation.repository.ReservationRepository
import com.ticketqueue.reservation.repository.ReservationSeatRepository
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.function.Consumer

@DisplayName("ExpireReservationBatchService 단위 테스트")
class ExpireReservationBatchServiceTest {

    private lateinit var reservationRepository: ReservationRepository
    private lateinit var reservationSeatRepository: ReservationSeatRepository
    private lateinit var outboxEventRecorder: OutboxEventRecorder
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var stringRedisTemplate: StringRedisTemplate
    private lateinit var setOps: SetOperations<String, String>
    private lateinit var batch: ExpireReservationBatchService

    @BeforeEach
    fun setUp() {
        reservationRepository = mockk()
        reservationSeatRepository = mockk()
        outboxEventRecorder = mockk()
        transactionTemplate = mockk()
        stringRedisTemplate = mockk()
        setOps = mockk()

        every { stringRedisTemplate.opsForSet() } returns setOps

        // transactionTemplate.executeWithoutResult를 즉시 실행하면서,
        // 콜백이 등록한 TransactionSynchronization.afterCommit도 invoke한다 (실제 commit 후 동작 시뮬레이션).
        every { transactionTemplate.executeWithoutResult(any()) } answers {
            val action = firstArg<Consumer<TransactionStatus>>()
            TransactionSynchronizationManager.initSynchronization()
            try {
                action.accept(mockk(relaxed = true))
                TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            } finally {
                TransactionSynchronizationManager.clear()
            }
        }

        batch = ExpireReservationBatchService(
            reservationRepository,
            reservationSeatRepository,
            outboxEventRecorder,
            transactionTemplate,
            stringRedisTemplate
        )
    }

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear()
        }
    }

    @Test
    @DisplayName("만료된 PENDING 예매가 없으면 추가 조회/저장 없이 종료한다")
    fun noopWhenNoCandidates() {
        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns emptyList()

        batch.expirePendingReservations()

        verify(exactly = 1) {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        }
        verify(exactly = 0) { transactionTemplate.executeWithoutResult(any()) }
        verify(exactly = 0) { setOps.remove(any<String>(), *anyVararg()) }
        confirmVerified(reservationRepository, reservationSeatRepository, outboxEventRecorder, setOps)
    }

    @Test
    @DisplayName("PENDING + 만료된 예매를 CANCELLED로 전이하고 Outbox 발행 + hold_seats SREM을 수행한다")
    fun cancelsExpiredReservation() {
        val reservationId = UUID.randomUUID()
        val scheduleId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val seatIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val reservation = pendingReservation(reservationId, scheduleId, userId, holdExpiresAt = pastMinutes(10))

        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns listOf(reservation)
        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationSeatRepository.findByReservationId(reservationId) } returns
            seatIds.map { reservationSeat(reservationId, it) }

        val eventSlot = slot<ReservationCancelledEvent>()
        every { outboxEventRecorder.record(capture(eventSlot)) } answers { mockk(relaxed = true) }
        every { setOps.remove(any<String>(), *anyVararg()) } returns seatIds.size.toLong()

        batch.expirePendingReservations()

        reservation.status shouldBe ReservationStatus.CANCELLED
        eventSlot.captured.aggregateId shouldBe reservationId
        eventSlot.captured.scheduleId shouldBe scheduleId
        eventSlot.captured.userId shouldBe userId
        eventSlot.captured.seatIds shouldBe seatIds
        eventSlot.captured.reason shouldBe "HOLD_EXPIRED"
        eventSlot.captured.eventType shouldBe "ReservationCancelled"

        verify(exactly = 1) {
            setOps.remove(
                "hold_seats:$scheduleId",
                *seatIds.map { it.toString() }.toTypedArray()
            )
        }
    }

    @Test
    @DisplayName("좌석이 비어 있는 예매는 SREM은 호출하지 않지만 Outbox 이벤트는 발행한다")
    fun publishesEventEvenWithoutSeats() {
        val reservationId = UUID.randomUUID()
        val reservation = pendingReservation(reservationId, holdExpiresAt = pastMinutes(2))

        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns listOf(reservation)
        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationSeatRepository.findByReservationId(reservationId) } returns emptyList()

        val eventSlot = slot<ReservationCancelledEvent>()
        every { outboxEventRecorder.record(capture(eventSlot)) } answers { mockk(relaxed = true) }

        batch.expirePendingReservations()

        reservation.status shouldBe ReservationStatus.CANCELLED
        eventSlot.captured.seatIds shouldBe emptyList()
        verify(exactly = 0) { setOps.remove(any<String>(), *anyVararg()) }
    }

    @Test
    @DisplayName("fresh re-fetch에서 상태가 이미 PENDING이 아니면 cancel/Outbox를 호출하지 않는다")
    fun skipsWhenStatusChangedConcurrently() {
        val reservationId = UUID.randomUUID()
        val candidate = pendingReservation(reservationId, holdExpiresAt = pastMinutes(5))
        val freshConfirmed = pendingReservation(reservationId, holdExpiresAt = pastMinutes(5))
            .apply { status = ReservationStatus.CONFIRMED }

        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns listOf(candidate)
        every { reservationRepository.findById(reservationId) } returns Optional.of(freshConfirmed)

        batch.expirePendingReservations()

        verify(exactly = 0) { reservationSeatRepository.findByReservationId(any()) }
        verify(exactly = 0) { outboxEventRecorder.record(any()) }
        verify(exactly = 0) { setOps.remove(any<String>(), *anyVararg()) }
    }

    @Test
    @DisplayName("fresh re-fetch에서 holdExpiresAt이 갱신되어 아직 미래라면 cancel하지 않는다")
    fun skipsWhenHoldExpiresAtExtended() {
        val reservationId = UUID.randomUUID()
        val candidate = pendingReservation(reservationId, holdExpiresAt = pastMinutes(5))
        val freshExtended = pendingReservation(
            reservationId,
            holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
        )

        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns listOf(candidate)
        every { reservationRepository.findById(reservationId) } returns Optional.of(freshExtended)

        batch.expirePendingReservations()

        verify(exactly = 0) { outboxEventRecorder.record(any()) }
        verify(exactly = 0) { setOps.remove(any<String>(), *anyVararg()) }
    }

    @Test
    @DisplayName("fresh re-fetch에서 예매가 삭제된 경우 조용히 skip한다")
    fun skipsWhenReservationDeleted() {
        val reservationId = UUID.randomUUID()
        val candidate = pendingReservation(reservationId, holdExpiresAt = pastMinutes(1))

        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns listOf(candidate)
        every { reservationRepository.findById(reservationId) } returns Optional.empty()

        batch.expirePendingReservations()

        verify(exactly = 0) { outboxEventRecorder.record(any()) }
        verify(exactly = 0) { reservationSeatRepository.findByReservationId(any()) }
    }

    @Test
    @DisplayName("한 건의 트랜잭션이 예외를 던져도 나머지 후보는 계속 처리한다")
    fun isolatesFailuresPerReservation() {
        val failingId = UUID.randomUUID()
        val succeedingId = UUID.randomUUID()
        val failingReservation = pendingReservation(failingId, holdExpiresAt = pastMinutes(3))
        val succeedingReservation = pendingReservation(succeedingId, holdExpiresAt = pastMinutes(3))

        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns listOf(failingReservation, succeedingReservation)
        every { reservationRepository.findById(failingId) } throws RuntimeException("DB blip")
        every { reservationRepository.findById(succeedingId) } returns Optional.of(succeedingReservation)
        every { reservationSeatRepository.findByReservationId(succeedingId) } returns emptyList()

        val eventSlot = slot<ReservationCancelledEvent>()
        every { outboxEventRecorder.record(capture(eventSlot)) } answers { mockk(relaxed = true) }

        batch.expirePendingReservations()

        succeedingReservation.status shouldBe ReservationStatus.CANCELLED
        failingReservation.status shouldBe ReservationStatus.PENDING
        eventSlot.captured.aggregateId shouldBe succeedingId
    }

    @Test
    @DisplayName("Redis SREM 실패는 배치 전체를 실패시키지 않는다 (TTL 보정 위임)")
    fun swallowsRedisFailures() {
        val reservationId = UUID.randomUUID()
        val reservation = pendingReservation(reservationId, holdExpiresAt = pastMinutes(2))
        val seatId = UUID.randomUUID()

        every {
            reservationRepository.findAllByStatusAndHoldExpiresAtBefore(ReservationStatus.PENDING, any())
        } returns listOf(reservation)
        every { reservationRepository.findById(reservationId) } returns Optional.of(reservation)
        every { reservationSeatRepository.findByReservationId(reservationId) } returns
            listOf(reservationSeat(reservationId, seatId))
        every { outboxEventRecorder.record(any()) } answers { mockk(relaxed = true) }
        every { setOps.remove(any<String>(), *anyVararg()) } throws RuntimeException("redis down")

        // 예외가 전파되지 않아야 한다
        batch.expirePendingReservations()

        reservation.status shouldBe ReservationStatus.CANCELLED
        verify(exactly = 1) { outboxEventRecorder.record(any()) }
    }

    // ---- Fixtures ----

    private fun pendingReservation(
        id: UUID,
        scheduleId: UUID = UUID.randomUUID(),
        userId: UUID = UUID.randomUUID(),
        holdExpiresAt: LocalDateTime
    ): Reservation = Reservation(
        id = id,
        userId = userId,
        scheduleId = scheduleId,
        eventId = UUID.randomUUID(),
        status = ReservationStatus.PENDING,
        totalAmount = BigDecimal("10000"),
        holdExpiresAt = holdExpiresAt
    )

    private fun reservationSeat(reservationId: UUID, seatId: UUID): ReservationSeat = ReservationSeat(
        id = UUID.randomUUID(),
        reservationId = reservationId,
        seatId = seatId,
        seatNumber = "A1",
        grade = "VIP",
        price = BigDecimal("10000")
    )

    private fun pastMinutes(minutes: Long): LocalDateTime =
        LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutes)
}
