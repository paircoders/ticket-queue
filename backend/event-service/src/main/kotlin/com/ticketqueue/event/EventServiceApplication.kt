package com.ticketqueue.event

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.ticketqueue.event", "com.ticketqueue.common"])
@EnableJpaRepositories(basePackages = ["com.ticketqueue.event.repository"])
@EntityScan(basePackages = ["com.ticketqueue.event.entity"])
@EnableScheduling
class EventServiceApplication

fun main(args: Array<String>) {
    runApplication<EventServiceApplication>(*args)
}
