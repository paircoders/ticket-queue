package com.ticketqueue.common.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.nio.charset.StandardCharsets

class ExternalApiLoggingInterceptor : ClientHttpRequestInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

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
        log.info(">>> External Request: [{} {}] Body: {}",
            request.method, request.uri, String(body, StandardCharsets.UTF_8))
    }

    private fun logResponse(response: ClientHttpResponse) {
        log.info("<<< External Response: [{}]", response.statusCode)
    }
}
