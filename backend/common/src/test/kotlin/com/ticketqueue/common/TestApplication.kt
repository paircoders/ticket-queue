package com.ticketqueue.common

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling
import com.ticketqueue.common.outbox.OutboxPollerProperties

@EnableConfigurationProperties(OutboxPollerProperties::class)
@SpringBootApplication
@EnableJpaRepositories(basePackages = ["com.ticketqueue.common.outbox"])
@EnableKafka
@EnableScheduling
@ComponentScan(basePackages = ["com.ticketqueue.common"])
class TestApplication

fun main(args: Array<String>) {
    runApplication<TestApplication>(*args)
}
