package com.ticketqueue.queue.scheduler

import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.service.QueueService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.UUID

@DisplayName("QueueCleanupScheduler 단위 테스트")
class QueueCleanupSchedulerTest {

    private lateinit var eventServiceClient: EventServiceClient
    private lateinit var queueService: QueueService
    private lateinit var scheduler: QueueCleanupScheduler

    @BeforeEach
    fun setUp() {
        eventServiceClient = mockk()
        queueService = mockk()
        scheduler = QueueCleanupScheduler(eventServiceClient, queueService)
    }

    @Test
    @DisplayName("Event Service 장애 시 cleanupEndedSchedule을 호출하지 않는다")
    fun doesNotCallCleanupWhenEventServiceFails() {
        every { eventServiceClient.getEndedScheduleIds() } throws RuntimeException("Connection refused")

        scheduler.executeCleanup()

        verify(exactly = 0) { queueService.cleanupEndedSchedule(any()) }
    }

    @Test
    @DisplayName("빈 목록 반환 시 cleanupEndedSchedule을 호출하지 않는다")
    fun doesNotCallCleanupWhenEmptyList() {
        every { eventServiceClient.getEndedScheduleIds() } returns
            EventServiceClient.EndedScheduleIdsResponse(emptyList())

        scheduler.executeCleanup()

        verify(exactly = 0) { queueService.cleanupEndedSchedule(any()) }
    }

    @Test
    @DisplayName("정상 3개 scheduleId 반환 시 cleanupEndedSchedule을 3번 호출한다")
    fun callsCleanupForEachEndedSchedule() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        every { eventServiceClient.getEndedScheduleIds() } returns
            EventServiceClient.EndedScheduleIdsResponse(listOf(id1, id2, id3))
        every { queueService.cleanupEndedSchedule(any()) } returns true

        scheduler.executeCleanup()

        verify(exactly = 1) { queueService.cleanupEndedSchedule(id1) }
        verify(exactly = 1) { queueService.cleanupEndedSchedule(id2) }
        verify(exactly = 1) { queueService.cleanupEndedSchedule(id3) }
    }

    @Test
    @DisplayName("개별 scheduleId 처리 실패 시 나머지 scheduleId를 계속 처리한다")
    fun continuesProcessingRemainingSchedulesOnIndividualFailure() {
        val failingId = UUID.randomUUID()
        val successId = UUID.randomUUID()
        every { eventServiceClient.getEndedScheduleIds() } returns
            EventServiceClient.EndedScheduleIdsResponse(listOf(failingId, successId))
        every { queueService.cleanupEndedSchedule(failingId) } throws RuntimeException("Redis timeout")
        every { queueService.cleanupEndedSchedule(successId) } returns true

        scheduler.executeCleanup()

        verify(exactly = 1) { queueService.cleanupEndedSchedule(failingId) }
        verify(exactly = 1) { queueService.cleanupEndedSchedule(successId) }
    }

    @Test
    @DisplayName("모든 scheduleId 처리가 실패해도 예외 없이 완료된다")
    fun completesWithoutExceptionWhenAllFail() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        every { eventServiceClient.getEndedScheduleIds() } returns
            EventServiceClient.EndedScheduleIdsResponse(listOf(id1, id2))
        every { queueService.cleanupEndedSchedule(any()) } throws RuntimeException("Redis down")

        assertDoesNotThrow { scheduler.executeCleanup() }

        verify(exactly = 1) { queueService.cleanupEndedSchedule(id1) }
        verify(exactly = 1) { queueService.cleanupEndedSchedule(id2) }
    }
}
