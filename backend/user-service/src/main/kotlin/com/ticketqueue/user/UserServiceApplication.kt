package com.ticketqueue.user

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["com.ticketqueue.user", "com.ticketqueue.common"])
@EnableJpaRepositories(basePackages = ["com.ticketqueue.user.repository"])
@EntityScan(basePackages = ["com.ticketqueue.user.entity"])
@EnableFeignClients
class UserServiceApplication

fun main(args: Array<String>) {
    runApplication<UserServiceApplication>(*args)
}
