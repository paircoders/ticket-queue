package com.ticketqueue.common.kafka

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.util.backoff.ExponentialBackOff

@Configuration
@ConditionalOnClass(KafkaTemplate::class)
class KafkaErrorHandlerConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Value("\${spring.kafka.bootstrap-servers:localhost:9092}")
    private lateinit var bootstrapServers: String

    companion object {
        private val DLQ_TOPIC_MAP = mapOf(
            "reservation.events" to "dlq.reservation",
            "payment.events" to "dlq.payment",
        )
    }

    @Bean
    fun dlqKafkaTemplate(): KafkaTemplate<String, Any> {
        val producerProps = mutableMapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JsonSerializer::class.java,
            ProducerConfig.ACKS_CONFIG to "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
        )
        val producerFactory = DefaultKafkaProducerFactory<String, Any>(producerProps)
        return KafkaTemplate(producerFactory)
    }

    @Bean
    fun deadLetterPublishingRecoverer(
        dlqKafkaTemplate: KafkaTemplate<String, Any>,
    ): DeadLetterPublishingRecoverer {
        return DeadLetterPublishingRecoverer(dlqKafkaTemplate) { record, _ ->
            val originalTopic = record.topic()
            val dlqTopic = DLQ_TOPIC_MAP[originalTopic] ?: "dlq.$originalTopic"
            log.warn("Sending failed record to DLQ: {} -> {}", originalTopic, dlqTopic)
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

        log.info(
            "Kafka error handler configured: backoff=exponential(1s/2x/10s), maxRetries=3, nonRetryable={}",
            ExceptionClassifier.nonRetryableExceptions().map { it.simpleName },
        )

        return errorHandler
    }
}
