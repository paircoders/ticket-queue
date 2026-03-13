package com.ticketqueue.queue.scheduler

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.service.QueueService
import feign.Request
import feign.RequestTemplate
import feign.RetryableException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.data.redis.RedisConnectionFailureException
import java.util.UUID

@DisplayName("QueueCleanupScheduler 단위 테스트")
class QueueCleanupSchedulerTest {

    private lateinit var eventServiceClient: EventServiceClient
    private lateinit var queueService: QueueService
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var scheduler: QueueCleanupScheduler

    @BeforeEach
    fun setUp() {
        eventServiceClient = mockk()
        queueService = mockk()
        registry = SimpleMeterRegistry()
        scheduler = QueueCleanupScheduler(eventServiceClient, queueService, registry)
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

    @Test
    @DisplayName("RetryableException 발생 시 failedCounter가 1 증가하고 cleanup은 호출되지 않는다")
    fun failedCounterIncreasesOnRetryableException() {
        val feignRequest = Request.create(
            Request.HttpMethod.GET,
            "http://event-service/internal/schedules/ended",
            emptyMap<String, Collection<String>>(),
            null as Request.Body?,
            null as RequestTemplate?
        )
        every { eventServiceClient.getEndedScheduleIds() } throws
            RetryableException(503, "Service Unavailable", Request.HttpMethod.GET, null as Long?, feignRequest)

        scheduler.executeCleanup()

        verify(exactly = 0) { queueService.cleanupEndedSchedule(any()) }
        assertEquals(1.0, registry.counter("queue.cleanup.failed.total").count())
    }

    @Test
    @DisplayName("BusinessException 발생 시 failedCounter가 1 증가하고 cleanup은 호출되지 않는다")
    fun failedCounterIncreasesOnBusinessException() {
        every { eventServiceClient.getEndedScheduleIds() } throws
            BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)

        scheduler.executeCleanup()

        verify(exactly = 0) { queueService.cleanupEndedSchedule(any()) }
        assertEquals(1.0, registry.counter("queue.cleanup.failed.total").count())
    }

    @Test
    @DisplayName("RedisConnectionFailureException 발생 시 failedCounter가 1 증가하고 나머지 scheduleId를 계속 처리한다")
    fun failedCounterIncreasesOnRedisConnectionFailure() {
        val failingId = UUID.randomUUID()
        val successId = UUID.randomUUID()
        every { eventServiceClient.getEndedScheduleIds() } returns
            EventServiceClient.EndedScheduleIdsResponse(listOf(failingId, successId))
        every { queueService.cleanupEndedSchedule(failingId) } throws
            RedisConnectionFailureException("connection failed")
        every { queueService.cleanupEndedSchedule(successId) } returns true

        scheduler.executeCleanup()

        verify(exactly = 1) { queueService.cleanupEndedSchedule(successId) }
        assertEquals(1.0, registry.counter("queue.cleanup.failed.total").count())
        assertEquals(1.0, registry.counter("queue.cleanup.deleted.total").count())
    }

    @Test
    @DisplayName("정상 처리 시 executedCounter=1, deletedCounter=3이 된다")
    fun metricsAreCorrectOnSuccess() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        every { eventServiceClient.getEndedScheduleIds() } returns
            EventServiceClient.EndedScheduleIdsResponse(listOf(id1, id2, id3))
        every { queueService.cleanupEndedSchedule(any()) } returns true

        scheduler.executeCleanup()

        assertEquals(1.0, registry.counter("queue.cleanup.executed.total").count())
        assertEquals(3.0, registry.counter("queue.cleanup.deleted.total").count())
        assertEquals(0.0, registry.counter("queue.cleanup.failed.total").count())
    }

    @Test
    @DisplayName("deleted/skipped 혼합 시나리오에서 deletedCounter는 실제 삭제된 수만 증가한다")
    fun deletedCounterOnlyCountsActualDeletions() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        every { eventServiceClient.getEndedScheduleIds() } returns
            EventServiceClient.EndedScheduleIdsResponse(listOf(id1, id2, id3))
        every { queueService.cleanupEndedSchedule(id1) } returns true
        every { queueService.cleanupEndedSchedule(id2) } returns true
        every { queueService.cleanupEndedSchedule(id3) } returns false

        scheduler.executeCleanup()

        assertEquals(2.0, registry.counter("queue.cleanup.deleted.total").count())
        assertEquals(0.0, registry.counter("queue.cleanup.failed.total").count())
    }
}
