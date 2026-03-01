package com.ticketqueue.common.external.portone

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "external.portone")
data class PortoneProperties(
    val apiUrl: String = "https://api.portone.io",
    val apiSecret: String? = null,
    val storeId: String? = null,
    val channelKey: String? = null
) {
    init {
        require(!apiSecret.isNullOrBlank()) { "external.portone.api-secret must not be blank" }
    }
}
