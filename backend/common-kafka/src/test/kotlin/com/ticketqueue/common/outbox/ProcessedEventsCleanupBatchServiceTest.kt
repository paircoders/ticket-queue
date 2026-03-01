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
class ProcessedEventsCleanupBatchServiceTest {

    @MockK
    private lateinit var processedEventRepository: ProcessedEventRepository

    private lateinit var service: ProcessedEventsCleanupBatchService

    @BeforeEach
    fun setUp() {
        service = ProcessedEventsCleanupBatchService(processedEventRepository, retentionDays = 30L)
    }

    @Test
    fun `처리 완료된 이벤트 정리 성공`() {
        // given
        val expectedDeletedCount = 10
        every { processedEventRepository.deleteByProcessedAtBefore(any()) } returns expectedDeletedCount

        // when
        service.cleanupOldProcessedEvents()

        // then
        verify(exactly = 1) {
            processedEventRepository.deleteByProcessedAtBefore(
                match { it.isBefore(LocalDateTime.now(ZoneOffset.UTC)) && it.isAfter(LocalDateTime.now(ZoneOffset.UTC).minusDays(31)) }
            )
        }
    }

    @Test
    fun `삭제 대상이 없는 경우`() {
        // given
        every { processedEventRepository.deleteByProcessedAtBefore(any()) } returns 0

        // when & then (예외 없이 정상 동작)
        service.cleanupOldProcessedEvents()

        verify(exactly = 1) {
            processedEventRepository.deleteByProcessedAtBefore(any())
        }
    }

    @Test
    fun `cutoff 날짜가 retentionDays 기반으로 계산되는지 확인`() {
        // given
        val fixedNow = LocalDateTime.of(2026, 2, 8, 14, 30, 0)
        val expectedCutoff = fixedNow.minusDays(30)

        mockkStatic(LocalDateTime::class)
        every { LocalDateTime.now(ZoneOffset.UTC) } returns fixedNow

        val capturedCutoff = slot<LocalDateTime>()
        every { processedEventRepository.deleteByProcessedAtBefore(capture(capturedCutoff)) } returns 3

        // when
        service.cleanupOldProcessedEvents()

        // then
        capturedCutoff.captured shouldBe expectedCutoff

        // cleanup
        unmockkStatic(LocalDateTime::class)
    }

    @Test
    fun `다수의 이벤트가 삭제된 경우`() {
        // given
        val largeDeletedCount = 100
        every { processedEventRepository.deleteByProcessedAtBefore(any()) } returns largeDeletedCount

        // when & then (예외 없이 정상 동작)
        service.cleanupOldProcessedEvents()

        verify(exactly = 1) {
            processedEventRepository.deleteByProcessedAtBefore(any())
        }
    }

    @Test
    fun `retentionDays 커스텀 값 적용 확인`() {
        // given
        val customRetentionService = ProcessedEventsCleanupBatchService(processedEventRepository, retentionDays = 60L)
        val fixedNow = LocalDateTime.of(2026, 2, 8, 14, 30, 0)
        val expectedCutoff = fixedNow.minusDays(60)

        mockkStatic(LocalDateTime::class)
        every { LocalDateTime.now(ZoneOffset.UTC) } returns fixedNow

        val capturedCutoff = slot<LocalDateTime>()
        every { processedEventRepository.deleteByProcessedAtBefore(capture(capturedCutoff)) } returns 15

        // when
        customRetentionService.cleanupOldProcessedEvents()

        // then
        capturedCutoff.captured shouldBe expectedCutoff

        // cleanup
        unmockkStatic(LocalDateTime::class)
    }
}
