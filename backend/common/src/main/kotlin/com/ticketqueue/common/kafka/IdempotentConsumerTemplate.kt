package com.ticketqueue.common.kafka

import com.ticketqueue.common.event.BaseEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
@ConditionalOnClass(name = ["org.springframework.kafka.support.Acknowledgment"])
class IdempotentConsumerTemplate(
    private val processedEventService: ProcessedEventService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun <T : BaseEvent> process(
        event: T,
        consumerService: String,
        acknowledgment: Acknowledgment,
        businessLogic: (T) -> Unit,
    ) {
        val eventId = event.eventId
        val eventType = event.eventType
        val aggregateId = event.aggregateId

        log.debug(
            "Processing event: eventId={}, type={}, consumer={}",
            eventId, eventType, consumerService,
        )

        val isNew = processedEventService.tryRecord(
            eventId = eventId,
            consumerService = consumerService,
            aggregateId = aggregateId,
            eventType = eventType,
        )

        if (!isNew) {
            log.info("Skipping duplicate event: eventId={}, consumer={}", eventId, consumerService)
            acknowledgment.acknowledge()
            return
        }

        try {
            businessLogic(event)
            acknowledgment.acknowledge()
            log.debug("Event processed successfully: eventId={}, consumer={}", eventId, consumerService)
        } catch (e: Exception) {
            if (ExceptionClassifier.isRetryable(e)) {
                log.warn(
                    "Retryable error processing event: eventId={}, consumer={}, error={}",
                    eventId, consumerService, e.message,
                )
                processedEventService.deleteRecord(eventId, consumerService)
                throw e
            } else {
                log.error(
                    "Non-retryable error processing event: eventId={}, consumer={}, error={}",
                    eventId, consumerService, e.message, e,
                )
                throw e
            }
        }
    }
}
