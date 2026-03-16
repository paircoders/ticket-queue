package com.ticketqueue.queue.scheduler

import com.ticketqueue.queue.service.QueueService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 대기열 배치 승인 스케줄러 (REQ-QUEUE-005)
 *
 * 1초마다 활성 대기열을 조회하고, 회차별로 상위 10명을 승인하여 Queue Token을 발급한다.
 * fixedDelay: 이전 실행 완료 후 interval 대기 (fixedRate 아님 — 처리 지연 누적 방지)
 *
 * 처리량: 10명/초 × 3600초 = 36,000명/시간 (REQ-QUEUE-005)
 */
@Component
class BatchApproveScheduler(
    private val queueService: QueueService
) {

    private val logger = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${queue.batch.interval}")
    fun executeBatchApprove() {
        val scheduleIds = queueService.getActiveScheduleIds()
        if (scheduleIds.isEmpty()) return

        for (rawId in scheduleIds) {
            val scheduleId = try {
                UUID.fromString(rawId)
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Invalid scheduleId format in active-schedules, skipping: $rawId" }
                continue
            }

            try {
                queueService.batchApprove(scheduleId)
            } catch (e: Exception) {
                logger.error(e) { "Batch approve failed for scheduleId=$scheduleId, continuing with remaining schedules" }
            }
        }
    }
}
