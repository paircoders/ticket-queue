package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.gateway.security.RouteValidator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

private val log = KotlinLogging.logger {}

/**
 * Queue Token(X-Queue-Token) 형식 검증 필터 (Layer 1)
 *
 * 처리 순서:
 * 1. OPTIONS 요청 → 통과
 * 2. Queue Token 불필요 경로 → 통과
 * 3. X-Queue-Token 헤더 없거나 빈 값 → 401 QUEUE_TOKEN_MISSING
 * 4. 형식 검증 실패 (qr_ prefix + 소문자 UUID 불일치) → 401 QUEUE_TOKEN_INVALID
 * 5. 통과 → chain.filter() (헤더는 Gateway가 자동 downstream 전달)
 *
 * Layer 2 (Redis 직접 조회로 유효성 검증)는 각 downstream 서비스 담당.
 * 아키텍처 결정: docs/architecture/06_api_security.md 1.2.4 참고
 *
 * REQ-GW-016 (Queue Token 헤더 전달), REQ-QUEUE-010 (Queue Token 검증)
 *
 * @Order(HIGHEST_PRECEDENCE + 3): JWT 필터(+2) 이후 실행
 * Queue Token 경로는 모두 인증 필수이므로 JWT 검증 실패 시 Queue Token 체크 불필요
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class QueueTokenWebFilter(
    private val routeValidator: RouteValidator,
    private val objectMapper: ObjectMapper,
) : WebFilter {

    companion object {
        const val QUEUE_TOKEN_HEADER = "X-Queue-Token"

        // 에러 코드 상수 (common 모듈 의존 불가로 인라인 정의 — WebFlux vs Servlet)
        private const val CODE_QUEUE_TOKEN_MISSING = "QUEUE_TOKEN_MISSING"
        private const val CODE_QUEUE_TOKEN_INVALID = "QUEUE_TOKEN_INVALID"

        // qr_ prefix + 소문자 UUID 형식 (UUID.randomUUID().toString() 출력 형식)
        private val QUEUE_TOKEN_PATTERN =
            Regex("^qr_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // CORS preflight (OPTIONS) 요청은 Queue Token 검증 없이 통과
        if (exchange.request.method == HttpMethod.OPTIONS) {
            return chain.filter(exchange)
        }

        // Queue Token이 불필요한 경로는 통과
        if (!routeValidator.isQueueTokenRequired(exchange)) {
            return chain.filter(exchange)
        }

        // X-Queue-Token 헤더 추출
        val queueToken = exchange.request.headers.getFirst(QUEUE_TOKEN_HEADER)
        if (queueToken.isNullOrBlank()) {
            log.debug { "Missing X-Queue-Token header: ${exchange.request.path}" }
            return writeErrorResponse(
                exchange,
                HttpStatus.UNAUTHORIZED,
                CODE_QUEUE_TOKEN_MISSING,
                "대기열 토큰이 필요합니다.",
            )
        }

        // 형식 검증: qr_ prefix + 소문자 UUID
        if (!QUEUE_TOKEN_PATTERN.matches(queueToken)) {
            log.debug { "Invalid X-Queue-Token format: path=${exchange.request.path}" }
            return writeErrorResponse(
                exchange,
                HttpStatus.UNAUTHORIZED,
                CODE_QUEUE_TOKEN_INVALID,
                "유효하지 않은 대기열 토큰입니다.",
            )
        }

        // 통과 — X-Queue-Token 헤더는 Gateway가 자동으로 downstream에 전달
        return chain.filter(exchange)
    }

    private fun writeErrorResponse(
        exchange: ServerWebExchange,
        status: HttpStatus,
        code: String,
        message: String,
    ): Mono<Void> = WebFilterErrorResponseWriter.write(exchange, objectMapper, status, code, message)
}
