package com.ticketqueue.reservation.config

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedissonConfig(
    @Value("\${redisson.single-server-config.address}")
    private val redisAddress: String
) {

    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config()
        config.useSingleServer()
            .setAddress(redisAddress)
            .setConnectionMinimumIdleSize(5)
            .setConnectionPoolSize(10)

        return Redisson.create(config)
    }
}
