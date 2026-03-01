package com.ticketqueue.common

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableKafka
@EnableScheduling
@ComponentScan(basePackages = ["com.ticketqueue.common"])
class TestApplication

fun main(args: Array<String>) {
    runApplication<TestApplication>(*args)
}
