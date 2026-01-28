package com.ticketqueue.common.security

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class InternalApiKeyValidator(
    @Value("\${internal.api.key:}")
    private val internalApiKey: String
) {
    companion object {
        const val HEADER_NAME = "X-Service-Api-Key"
    }

    fun validate(apiKey: String?) {
        if (internalApiKey.isBlank()) {
            throw IllegalStateException("Internal API key is not configured")
        }

        if (apiKey.isNullOrBlank() || apiKey != internalApiKey) {
            throw BusinessException(ErrorCode.FORBIDDEN, "Invalid internal API key")
        }
    }
}
