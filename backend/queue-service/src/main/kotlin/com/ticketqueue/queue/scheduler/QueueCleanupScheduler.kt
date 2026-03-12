package com.ticketqueue.queue.scheduler

import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.service.QueueService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 종료된 회차의 대기열 Redis 키 정리 배치 (Issue #125)
 *
 * 매일 03:00 KST 실행:
 * 1. Event Service 내부 API로 종료 + 24시간 경과 회차 ID 목록 조회
 * 2. 해당 회차의 Redis 키 삭제 (queue:{scheduleId}, active-schedules SREM)
 * 3. 실행 결과 로깅 (성공/삭제 건수/실패 건수)
 *
 * Event Service 장애 시: 예외 로그 후 다음 실행 주기까지 대기 (데이터 유실 위험 없음)
 */
@Component
class QueueCleanupScheduler(
    private val eventServiceClient: EventServiceClient,
    private val queueService: QueueService
) {

    private val logger = KotlinLogging.logger {}

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    fun executeCleanup() {
        MDC.put("traceId", UUID.randomUUID().toString())
        try {
            logger.info { "Queue cleanup batch started" }

            val scheduleIds = try {
                eventServiceClient.getEndedScheduleIds().scheduleIds
            } catch (e: Exception) {
                logger.error(e) { "Queue cleanup batch failed: unable to fetch ended schedules from Event Service" }
                return
            }

            if (scheduleIds.isEmpty()) {
                logger.info { "Queue cleanup batch completed: no ended schedules to clean up" }
                return
            }

            var deletedCount = 0
            var skippedCount = 0
            var failedCount = 0

            for (scheduleId in scheduleIds) {
                try {
                    val deleted = queueService.cleanupEndedSchedule(scheduleId)
                    if (deleted) deletedCount++ else skippedCount++
                    logger.debug { "Queue cleanup: scheduleId=$scheduleId, queueExisted=$deleted" }
                } catch (e: Exception) {
                    failedCount++
                    logger.error(e) { "Queue cleanup failed for scheduleId=$scheduleId, continuing with remaining schedules" }
                }
            }

            logger.info {
                "Queue cleanup batch completed: total=${scheduleIds.size}, deleted=$deletedCount, skipped=$skippedCount, failed=$failedCount"
            }
        } finally {
            MDC.remove("traceId")
        }
    }
}
