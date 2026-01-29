package com.ticketqueue.common.security

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class InternalApiKeyValidator(
    @Value("\${internal.api.key:}")
    private val internalApiKey: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val HEADER_NAME = "X-Service-Api-Key"
    }

    fun validate(request: HttpServletRequest) {
        if (internalApiKey.isBlank()) {
            throw IllegalStateException("Internal API key is not configured")
        }

        val apiKey = request.getHeader(HEADER_NAME)

        if (apiKey.isNullOrBlank() || apiKey != internalApiKey) {
            log.warn(
                "[INTERNAL_API_AUTH_FAILED] uri={}, method={}, remoteAddr={}, forwardedFor={}, keyPresent={}",
                request.requestURI,
                request.method,
                request.remoteAddr,
                request.getHeader("X-Forwarded-For"),
                !apiKey.isNullOrBlank()
            )
            throw BusinessException(ErrorCode.INTERNAL_API_UNAUTHORIZED)
        }
    }
}
