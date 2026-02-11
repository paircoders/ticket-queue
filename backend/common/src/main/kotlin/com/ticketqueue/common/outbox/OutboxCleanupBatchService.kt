package com.ticketqueue.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset

// outbox.cleanup.enabled=true 설정이 있을 때만 Bean 등록
// 서비스별로 정리 배치를 활성화/비활성화 가능
@Service
@ConditionalOnProperty(prefix = "outbox.cleanup", name = ["enabled"], havingValue = "true")
class OutboxCleanupBatchService(
    private val outboxEventRepository: OutboxEventRepository,
    // 서비스별로 담당하는 aggregateType 주입 (예: "Reservation", "Payment")
    @Value("\${outbox.cleanup.aggregate-type}") private val aggregateType: String,
    // 보관 주기 (기본 7일)
    @Value("\${outbox.cleanup.retention-days:7}") private val retentionDays: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 02:00 KST에 실행 (초 분 시 일 월 요일)
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Seoul")
    @Transactional
    fun cleanupPublishedEvents() {
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays)
        val deletedCount = outboxEventRepository.deletePublishedEventsBefore(
            aggregateType = aggregateType,
            before = cutoff
        )
        log.info(
            "Cleaned up {} published outbox events for aggregateType={} older than {} days",
            deletedCount,
            aggregateType,
            retentionDays
        )
    }
}
