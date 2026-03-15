package com.ticketqueue.common.kafka

import com.ticketqueue.common.event.BaseEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
@ConditionalOnClass(name = ["org.springframework.kafka.support.Acknowledgment"])
class IdempotentConsumerTemplate(
    private val processedEventService: ProcessedEventService,
) {

    private val logger = KotlinLogging.logger {}

    fun <T : BaseEvent> process(
        event: T,
        consumerService: String,
        acknowledgment: Acknowledgment,
        businessLogic: (T) -> Unit,
    ) {
        val eventId = event.eventId
        val eventType = event.eventType
        val aggregateId = event.aggregateId

        logger.debug { "Processing event: eventId=$eventId, type=$eventType, consumer=$consumerService" }

        val isNew = processedEventService.tryRecord(
            eventId = eventId,
            consumerService = consumerService,
            aggregateId = aggregateId,
            eventType = eventType,
        )

        if (!isNew) {
            logger.info { "Skipping duplicate event: eventId=$eventId, consumer=$consumerService" }
            acknowledgment.acknowledge()
            return
        }

        try {
            businessLogic(event)
            acknowledgment.acknowledge()
            logger.debug { "Event processed successfully: eventId=$eventId, consumer=$consumerService" }
        } catch (e: Exception) {
            if (ExceptionClassifier.isRetryable(e)) {
                logger.warn { "Retryable error processing event: eventId=$eventId, consumer=$consumerService, error=${e.message}" }
                processedEventService.deleteRecord(eventId, consumerService)
                throw e
            } else {
                logger.error(e) { "Non-retryable error processing event: eventId=$eventId, consumer=$consumerService, error=${e.message}" }
                processedEventService.deleteRecord(eventId, consumerService)
                throw e
            }
        }
    }
}
