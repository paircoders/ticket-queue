package com.ticketqueue.common.config

import com.ticketqueue.common.security.InternalApiKeyValidator
import feign.RequestInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnClass(RequestInterceptor::class)
class InternalApiFeignConfig(
    @Value("\${internal_api_key}")
    private val internalApiKey: String
) {

    @Bean
    fun internalApiKeyRequestInterceptor(): RequestInterceptor {
        return RequestInterceptor { template ->
            template.header(InternalApiKeyValidator.HEADER_NAME, internalApiKey)
        }
    }
}
