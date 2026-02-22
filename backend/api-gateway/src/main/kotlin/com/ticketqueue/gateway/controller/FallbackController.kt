package com.ticketqueue.gateway.controller

import com.ticketqueue.gateway.filter.TraceIdWebFilter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * Circuit Breaker Fallback 컨트롤러
 *
 * 다운스트림 서비스 장애 시 503 Service Unavailable 응답을 반환합니다.
 * Circuit Breaker 필터의 `fallbackUri: forward:/fallback/{serviceName}`으로 내부 포워드 호출됩니다.
 *
 * forward: 방식은 WebFilter 체인을 재실행하지 않으므로,
 * JWT 검증이 이미 완료된 상태에서 이 컨트롤러가 호출됩니다.
 *
 * REQ-GW-006 (Circuit Breaker), REQ-GW-017 (Fallback 응답 503)
 */
@RestController
@RequestMapping("/fallback")
class FallbackController {

    companion object {
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private const val ERROR_CODE = "SERVICE_UNAVAILABLE"
        private const val DEFAULT_MESSAGE = "서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요."

        private val SERVICE_MESSAGES = mapOf(
            "user" to "인증 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.",
            "event" to "공연 정보 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.",
            "queue" to "대기열 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.",
            "reservation" to "예매 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.",
            "payment" to "결제 서비스가 일시적으로 불안정합니다. 이중 결제를 방지하기 위해 결제 내역을 확인한 후 다시 시도해주세요.",
        )
    }

    /**
     * 모든 HTTP 메서드에 대해 서비스별 503 응답 반환
     *
     * @param serviceName 서비스명 (user, event, queue, reservation, payment)
     * @param exchange 원본 요청 exchange (TraceId 헤더 추출용)
     */
    @RequestMapping("/{serviceName}")
    fun fallback(
        @PathVariable serviceName: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<Map<String, Any>> {
        val traceId = exchange.request.headers.getFirst(TraceIdWebFilter.TRACE_ID_HEADER) ?: "unknown"
        val message = SERVICE_MESSAGES[serviceName] ?: DEFAULT_MESSAGE

        val body: Map<String, Any> = mapOf(
            "code" to ERROR_CODE,
            "message" to message,
            "timestamp" to LocalDateTime.now().format(TIMESTAMP_FORMATTER),
            "traceId" to traceId,
        )

        log.warn { "Circuit Breaker fallback: service=$serviceName, traceId=$traceId, path=${exchange.request.path}" }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body)
    }
}
