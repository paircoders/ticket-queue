package com.ticketqueue.common.outbox

import io.kotest.matchers.shouldBe
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.LocalDateTime
import java.time.ZoneOffset

@ExtendWith(MockKExtension::class)
class OutboxCleanupBatchServiceTest {

    @MockK
    private lateinit var outboxEventRepository: OutboxEventRepository

    private lateinit var service: OutboxCleanupBatchService

    @BeforeEach
    fun setUp() {
        service = OutboxCleanupBatchService(outboxEventRepository, "Reservation", retentionDays = 7L)
    }

    @Test
    fun `발행 완료된 이벤트 정리 성공`() {
        // given
        val expectedDeletedCount = 10L
        every {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = "Reservation",
                before = any()
            )
        } returns expectedDeletedCount

        // when
        service.cleanupPublishedEvents()

        // then
        verify(exactly = 1) {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = "Reservation",
                before = match { it.isBefore(LocalDateTime.now(ZoneOffset.UTC)) && it.isAfter(LocalDateTime.now(ZoneOffset.UTC).minusDays(8)) }
            )
        }
    }

    @Test
    fun `삭제 대상이 없는 경우`() {
        // given
        every {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = any(),
                before = any()
            )
        } returns 0L

        // when & then (예외 없이 정상 동작)
        service.cleanupPublishedEvents()

        verify(exactly = 1) {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = "Reservation",
                before = any()
            )
        }
    }

    @Test
    fun `aggregateType이 정확히 전달되는지 확인`() {
        // given
        val paymentService = OutboxCleanupBatchService(outboxEventRepository, "Payment", retentionDays = 7L)
        every {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = any(),
                before = any()
            )
        } returns 5L

        // when
        paymentService.cleanupPublishedEvents()

        // then
        verify(exactly = 1) {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = "Payment",
                before = any()
            )
        }
    }

    @Test
    fun `cutoff 날짜가 retentionDays 기반으로 계산되는지 확인`() {
        // given
        val fixedNow = LocalDateTime.of(2026, 2, 8, 14, 30, 0)
        val expectedCutoff = fixedNow.minusDays(7)

        mockkStatic(LocalDateTime::class)
        every { LocalDateTime.now(ZoneOffset.UTC) } returns fixedNow

        val capturedCutoff = slot<LocalDateTime>()
        every {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = any(),
                before = capture(capturedCutoff)
            )
        } returns 3L

        // when
        service.cleanupPublishedEvents()

        // then
        capturedCutoff.captured shouldBe expectedCutoff

        // cleanup
        unmockkStatic(LocalDateTime::class)
    }

    @Test
    fun `다수의 이벤트가 삭제된 경우`() {
        // given
        val largeDeletedCount = 100L
        every {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = any(),
                before = any()
            )
        } returns largeDeletedCount

        // when & then (예외 없이 정상 동작, 로깅 확인은 선택)
        service.cleanupPublishedEvents()

        verify(exactly = 1) {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = "Reservation",
                before = any()
            )
        }
    }

    @Test
    fun `retentionDays 커스텀 값 적용 확인`() {
        // given
        val customRetentionService = OutboxCleanupBatchService(outboxEventRepository, "Reservation", retentionDays = 30L)
        val fixedNow = LocalDateTime.of(2026, 2, 8, 14, 30, 0)
        val expectedCutoff = fixedNow.minusDays(30)

        mockkStatic(LocalDateTime::class)
        every { LocalDateTime.now(ZoneOffset.UTC) } returns fixedNow

        val capturedCutoff = slot<LocalDateTime>()
        every {
            outboxEventRepository.deletePublishedEventsBefore(
                aggregateType = any(),
                before = capture(capturedCutoff)
            )
        } returns 15L

        // when
        customRetentionService.cleanupPublishedEvents()

        // then
        capturedCutoff.captured shouldBe expectedCutoff

        // cleanup
        unmockkStatic(LocalDateTime::class)
    }
}
