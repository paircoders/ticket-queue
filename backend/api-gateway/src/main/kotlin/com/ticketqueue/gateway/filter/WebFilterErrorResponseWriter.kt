package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * WebFilter 에러 응답 공통 유틸리티
 *
 * JwtAuthenticationWebFilter와 QueueTokenWebFilter의 writeErrorResponse 로직을
 * 추출하여 DRY 원칙 적용.
 */
internal object WebFilterErrorResponseWriter {

    private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun write(
        exchange: ServerWebExchange,
        objectMapper: ObjectMapper,
        status: HttpStatus,
        code: String,
        message: String,
    ): Mono<Void> {
        val traceId = exchange.request.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
            ?: exchange.response.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER)
            ?: "unknown"

        val body = mapOf(
            "code" to code,
            "message" to message,
            "timestamp" to LocalDateTime.now(ZoneOffset.UTC).format(TIMESTAMP_FORMATTER),
            "traceId" to traceId,
        )

        val bytes = objectMapper.writeValueAsBytes(body)
        val buffer = exchange.response.bufferFactory().wrap(bytes)

        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON

        return exchange.response.writeWith(Mono.just(buffer))
    }
}
