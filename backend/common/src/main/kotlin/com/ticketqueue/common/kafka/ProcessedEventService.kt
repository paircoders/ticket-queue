package com.ticketqueue.common.kafka

import com.ticketqueue.common.outbox.ProcessedEvent
import com.ticketqueue.common.outbox.ProcessedEventId
import com.ticketqueue.common.outbox.ProcessedEventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
@ConditionalOnClass(name = ["org.springframework.kafka.support.Acknowledgment"])
class ProcessedEventService(
    private val processedEventRepository: ProcessedEventRepository,
) {

    private val logger = KotlinLogging.logger {}

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
            logger.debug { "Event already processed: eventId=$eventId, consumer=$consumerService" }
            false
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deleteRecord(eventId: UUID, consumerService: String) {
        processedEventRepository.deleteById(ProcessedEventId(eventId, consumerService))
        logger.debug { "Deleted processed event record for retry: eventId=$eventId, consumer=$consumerService" }
    }

    @Transactional
    fun cleanupOldEvents(retentionDays: Long = 7): Int {
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(retentionDays)
        val deletedCount = processedEventRepository.deleteByProcessedAtBefore(cutoff)
        logger.info { "Cleaned up $deletedCount processed events older than $retentionDays days" }
        return deletedCount
    }
}
