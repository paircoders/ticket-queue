package com.ticketqueue.common.config

import com.ticketqueue.common.security.InternalApiKeyValidator
import feign.RequestInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean

class InternalFeignConfig(
    @Value("\${internal.api.key}")
    private val internalApiKey: String
) {

    @Bean
    fun internalApiKeyRequestInterceptor(): RequestInterceptor {
        return RequestInterceptor { template ->
            template.header(InternalApiKeyValidator.HEADER_NAME, internalApiKey)
        }
    }
}
