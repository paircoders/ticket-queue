package com.ticketqueue.common.external.portone

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "external.portone")
data class PortoneProperties(
    val apiUrl: String = "https://api.portone.io",
    val apiSecret: String,
    val storeId: String? = null,
    val channelKey: String? = null
)
