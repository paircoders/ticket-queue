package com.ticketqueue.common.kafka

import com.ticketqueue.common.outbox.ProcessedEvent
import com.ticketqueue.common.outbox.ProcessedEventId
import com.ticketqueue.common.outbox.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class ProcessedEventService(
    private val processedEventRepository: ProcessedEventRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun tryRecord(
        eventId: UUID,
        consumerService: String,
        aggregateId: UUID,
        eventType: String,
    ): Boolean {
        return try {
            processedEventRepository.save(
                ProcessedEvent(
                    eventId = eventId,
                    consumerService = consumerService,
                    aggregateId = aggregateId,
                    eventType = eventType,
                )
            )
            true
        } catch (e: DataIntegrityViolationException) {
            log.debug("Event already processed: eventId={}, consumer={}", eventId, consumerService)
            false
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deleteRecord(eventId: UUID, consumerService: String) {
        processedEventRepository.deleteById(ProcessedEventId(eventId, consumerService))
        log.debug("Deleted processed event record for retry: eventId={}, consumer={}", eventId, consumerService)
    }

    @Transactional
    fun cleanupOldEvents(retentionDays: Long = 7): Int {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        val deletedCount = processedEventRepository.deleteByProcessedAtBefore(cutoff)
        log.info("Cleaned up {} processed events older than {} days", deletedCount, retentionDays)
        return deletedCount
    }
}
