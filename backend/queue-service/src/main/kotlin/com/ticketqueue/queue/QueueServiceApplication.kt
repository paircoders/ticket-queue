package com.ticketqueue.queue

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(
    scanBasePackages = ["com.ticketqueue.queue", "com.ticketqueue.common"],
    excludeName = [
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
    ]
)
class QueueServiceApplication

fun main(args: Array<String>) {
    runApplication<QueueServiceApplication>(*args)
}
