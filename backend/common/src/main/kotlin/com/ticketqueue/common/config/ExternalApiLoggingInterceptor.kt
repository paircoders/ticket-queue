package com.ticketqueue.common.config

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.nio.charset.StandardCharsets

class ExternalApiLoggingInterceptor : ClientHttpRequestInterceptor {
    private val logger = KotlinLogging.logger {}

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution
    ): ClientHttpResponse {
        logRequest(request, body)
        val response = execution.execute(request, body)
        logResponse(response)
        return response
    }

    private fun logRequest(request: HttpRequest, body: ByteArray) {
        val bodyString = String(body, StandardCharsets.UTF_8)
        logger.info { ">>> External Request: [${request.method} ${request.uri}] Body: $bodyString" }
    }

    private fun logResponse(response: ClientHttpResponse) {
        logger.info { "<<< External Response: [${response.statusCode}]" }
    }
}
