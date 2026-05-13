package com.ticketqueue.payment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.ticketqueue.payment", "com.ticketqueue.common"])
@EnableJpaRepositories(basePackages = ["com.ticketqueue.payment.repository"])
@EntityScan(basePackages = ["com.ticketqueue.payment.entity"])
@EnableFeignClients(basePackages = ["com.ticketqueue.payment", "com.ticketqueue.common"])
@EnableScheduling
class PaymentServiceApplication

fun main(args: Array<String>) {
    runApplication<PaymentServiceApplication>(*args)
}
