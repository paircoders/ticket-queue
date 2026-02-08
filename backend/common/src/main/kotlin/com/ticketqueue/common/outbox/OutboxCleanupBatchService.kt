package com.ticketqueue.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// outbox.cleanup.enabled=true 설정이 있을 때만 Bean 등록
// 서비스별로 정리 배치를 활성화/비활성화 가능
@Service
@ConditionalOnProperty(prefix = "outbox.cleanup", name = ["enabled"], havingValue = "true")
class OutboxCleanupBatchService(
    private val outboxEventRepository: OutboxEventRepository,
    // 서비스별로 담당하는 aggregateType 주입 (예: "Reservation", "Payment")
    @Value("\${outbox.cleanup.aggregate-type}") private val aggregateType: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 매일 02:00 UTC에 실행 (초 분 시 일 월 요일)
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    fun cleanupPublishedEvents() {
        // 7일 이전의 발행 완료된 이벤트 삭제
        val cutoff = LocalDateTime.now().minusDays(7)
        val deletedCount = outboxEventRepository.deleteByAggregateTypeAndPublishedTrueAndPublishedAtBefore(
            aggregateType = aggregateType,
            before = cutoff
        )
        log.info(
            "Cleaned up {} published outbox events for aggregateType={} older than 7 days",
            deletedCount,
            aggregateType
        )
    }
}
