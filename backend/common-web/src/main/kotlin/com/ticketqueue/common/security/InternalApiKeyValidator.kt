package com.ticketqueue.common.security

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class InternalApiKeyValidator(
    @Value("\${internal.api.key:}")
    private val internalApiKey: String
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val HEADER_NAME = "X-Service-Api-Key"
    }

    fun validate(request: HttpServletRequest) {
        if (internalApiKey.isBlank()) {
            throw IllegalStateException("Internal API key is not configured")
        }

        val apiKey = request.getHeader(HEADER_NAME)

        if (apiKey.isNullOrBlank() || !MessageDigest.isEqual(apiKey.toByteArray(), internalApiKey.toByteArray())) {
            logger.warn {
                "[INTERNAL_API_AUTH_FAILED] uri=${request.requestURI}, method=${request.method}, " +
                "remoteAddr=${request.remoteAddr}, forwardedFor=${request.getHeader("X-Forwarded-For")}, " +
                "keyPresent=${!apiKey.isNullOrBlank()}"
            }
            throw BusinessException(ErrorCode.INTERNAL_API_UNAUTHORIZED)
        }
    }
}
