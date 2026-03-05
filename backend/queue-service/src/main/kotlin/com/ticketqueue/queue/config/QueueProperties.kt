package com.ticketqueue.queue.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "queue")
data class QueueProperties(
    val batch: BatchProperties = BatchProperties(),
    val token: TokenProperties = TokenProperties(),
    val maxCapacity: Int = 50000,
    val activeUser: ActiveUserProperties = ActiveUserProperties(),
    val rateLimit: RateLimitProperties = RateLimitProperties()
) {
    data class BatchProperties(
        val size: Int = 10,
        val interval: Long = 1000
    )

    data class TokenProperties(
        val ttl: Long = 600
    )

    data class ActiveUserProperties(
        val ttl: Long = 600
    )

    data class RateLimitProperties(
        val maxRequests: Int = 15,
        val windowSeconds: Long = 60
    )
}
