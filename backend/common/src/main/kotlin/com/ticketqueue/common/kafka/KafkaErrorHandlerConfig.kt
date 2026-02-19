package com.ticketqueue.common.kafka

import com.ticketqueue.common.kafka.KafkaTopicConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff

@Configuration
@ConditionalOnClass(KafkaTemplate::class)
class KafkaErrorHandlerConfig {

    private val logger = KotlinLogging.logger {}

    @Bean
    fun deadLetterPublishingRecoverer(
        kafkaTemplate: KafkaTemplate<String, Any>,
    ): DeadLetterPublishingRecoverer {
        return DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            val originalTopic = record.topic()
            val dlqTopic = KafkaTopicConfig.resolveDlqTopic(originalTopic)
            logger.warn { "Sending failed record to DLQ: $originalTopic -> $dlqTopic" }
            org.apache.kafka.common.TopicPartition(dlqTopic, -1)
        }
    }

    @Bean
    fun kafkaErrorHandler(
        deadLetterPublishingRecoverer: DeadLetterPublishingRecoverer,
    ): CommonErrorHandler {
        val backOff = ExponentialBackOff().apply {
            initialInterval = 1_000L
            multiplier = 2.0
            maxInterval = 10_000L
            maxElapsedTime = 15_000L
        }

        val errorHandler = DefaultErrorHandler(deadLetterPublishingRecoverer, backOff)

        ExceptionClassifier.nonRetryableExceptions().forEach { exceptionClass ->
            errorHandler.addNotRetryableExceptions(exceptionClass)
        }

        logger.info {
            "Kafka error handler configured: backoff=exponential(1s/2x/10s), maxRetries=3, " +
            "nonRetryable=${ExceptionClassifier.nonRetryableExceptions().map { it.simpleName }}"
        }

        return errorHandler
    }
}
