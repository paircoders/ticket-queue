package com.ticketqueue.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset

// processed-event.cleanup.enabled=true 설정이 있을 때만 Bean 등록
// Consumer 서비스(Reservation, Event)에서만 활성화
@Service
@ConditionalOnProperty(prefix = "processed-event.cleanup", name = ["enabled"], havingValue = "true")
class ProcessedEventsCleanupBatchService(
    private val processedEventRepository: ProcessedEventRepository,
    // 보관 주기 (기본 30일)
    @Value("\${processed-event.cleanup.retention-days:30}") private val retentionDays: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 02:00 KST에 실행 (초 분 시 일 월 요일)
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Seoul")
    @Transactional
    fun cleanupOldProcessedEvents() {
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays)
        val deletedCount = processedEventRepository.deleteByProcessedAtBefore(cutoff)
        log.info(
            "Cleaned up {} processed events older than {} days",
            deletedCount,
            retentionDays
        )
    }
}
