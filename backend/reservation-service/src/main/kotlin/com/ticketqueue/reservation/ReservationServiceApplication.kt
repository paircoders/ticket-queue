package com.ticketqueue.reservation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.ticketqueue.reservation", "com.ticketqueue.common"])
@EnableJpaRepositories(basePackages = ["com.ticketqueue.reservation.repository"])
@EntityScan(basePackages = ["com.ticketqueue.reservation.entity"])
@EnableFeignClients
@EnableScheduling
class ReservationServiceApplication

fun main(args: Array<String>) {
    runApplication<ReservationServiceApplication>(*args)
}
