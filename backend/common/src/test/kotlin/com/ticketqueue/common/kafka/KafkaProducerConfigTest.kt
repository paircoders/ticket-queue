package com.ticketqueue.common.kafka

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.kafka.clients.producer.ProducerConfig
import org.junit.jupiter.api.Test

class KafkaProducerConfigTest {
    private val config = KafkaProducerConfig()

    @Test
    fun `producerFactory에 멱등성 설정이 올바르게 적용된다`() {
        val factory = config.producerFactory("localhost:9092")
        val props = factory.configurationProperties

        props[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] shouldBe true
        props[ProducerConfig.ACKS_CONFIG] shouldBe "all"
        props[ProducerConfig.RETRIES_CONFIG] shouldBe Int.MAX_VALUE
        props[ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION] shouldBe 5
    }

    @Test
    fun `producerFactory에 성능 최적화 설정이 적용된다`() {
        val factory = config.producerFactory("localhost:9092")
        val props = factory.configurationProperties

        props[ProducerConfig.COMPRESSION_TYPE_CONFIG] shouldBe "snappy"
        props[ProducerConfig.BATCH_SIZE_CONFIG] shouldBe 16384
        props[ProducerConfig.LINGER_MS_CONFIG] shouldBe 10
    }

    @Test
    fun `kafkaTemplate이 producerFactory를 사용한다`() {
        val factory = config.producerFactory("localhost:9092")
        val template = config.kafkaTemplate(factory)

        template shouldNotBe null
        template.producerFactory shouldBe factory
    }
}
