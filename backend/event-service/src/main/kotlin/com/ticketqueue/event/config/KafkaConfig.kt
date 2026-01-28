package com.ticketqueue.event.config

import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka

@Configuration
@EnableKafka
class KafkaConfig {
    // Kafka configuration - using Spring Boot auto-configuration
}
