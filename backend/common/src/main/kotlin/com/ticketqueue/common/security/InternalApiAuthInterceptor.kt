package com.ticketqueue.common.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class InternalApiAuthInterceptor(
    private val internalApiKeyValidator: InternalApiKeyValidator
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        internalApiKeyValidator.validate(request)
        return true
    }
}
