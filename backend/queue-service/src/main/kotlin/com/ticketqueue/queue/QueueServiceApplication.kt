package com.ticketqueue.queue

import com.ticketqueue.common.config.CommonJpaConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootApplication(
    exclude = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class
    ]
)
@ComponentScan(
    basePackages = ["com.ticketqueue.queue", "com.ticketqueue.common"],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [CommonJpaConfig::class]
        )
    ]
)
class QueueServiceApplication

fun main(args: Array<String>) {
    runApplication<QueueServiceApplication>(*args)
}
