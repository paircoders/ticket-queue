package com.ticketqueue.queue.scheduler

import com.ticketqueue.queue.service.QueueService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

@DisplayName("BatchApproveScheduler 단위 테스트")
class BatchApproveSchedulerTest {

    private lateinit var queueService: QueueService
    private lateinit var scheduler: BatchApproveScheduler

    @BeforeEach
    fun setUp() {
        queueService = mockk()
        scheduler = BatchApproveScheduler(queueService)
    }

    @Test
    @DisplayName("활성 스케줄이 없으면 batchApprove를 호출하지 않는다")
    fun doesNotCallBatchApproveWhenNoActiveSchedules() {
        every { queueService.getActiveScheduleIds() } returns emptySet()

        scheduler.executeBatchApprove()

        verify(exactly = 0) { queueService.batchApprove(any()) }
    }

    @Test
    @DisplayName("활성 스케줄마다 batchApprove를 호출한다")
    fun callsBatchApproveForEachActiveSchedule() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        every { queueService.getActiveScheduleIds() } returns setOf(id1.toString(), id2.toString())
        every { queueService.batchApprove(any()) } returns 5L

        scheduler.executeBatchApprove()

        verify(exactly = 1) { queueService.batchApprove(id1) }
        verify(exactly = 1) { queueService.batchApprove(id2) }
    }

    @Test
    @DisplayName("하나의 스케줄 처리 실패 시 나머지 스케줄을 계속 처리한다")
    fun continuesProcessingRemainingSchedulesOnFailure() {
        val failingId = UUID.randomUUID()
        val successId = UUID.randomUUID()
        // getActiveScheduleIds 반환 순서를 보장하기 위해 LinkedHashSet 사용
        every { queueService.getActiveScheduleIds() } returns linkedSetOf(failingId.toString(), successId.toString())
        every { queueService.batchApprove(failingId) } throws RuntimeException("Redis timeout")
        every { queueService.batchApprove(successId) } returns 3L

        scheduler.executeBatchApprove()

        verify(exactly = 1) { queueService.batchApprove(failingId) }
        verify(exactly = 1) { queueService.batchApprove(successId) }
    }

    @Test
    @DisplayName("유효하지 않은 UUID 형식의 scheduleId는 건너뛴다")
    fun skipsInvalidUuidFormat() {
        val validId = UUID.randomUUID()
        every { queueService.getActiveScheduleIds() } returns setOf("not-a-uuid", validId.toString())
        every { queueService.batchApprove(validId) } returns 2L

        scheduler.executeBatchApprove()

        verify(exactly = 1) { queueService.batchApprove(validId) }
        // "not-a-uuid"는 UUID.fromString 실패 → skip → batchApprove 호출 안 됨
        verify(exactly = 1) { queueService.batchApprove(any()) }
    }
}
