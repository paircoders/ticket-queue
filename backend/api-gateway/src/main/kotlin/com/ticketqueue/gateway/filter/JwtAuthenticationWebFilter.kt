package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.gateway.security.JwtTokenProvider
import com.ticketqueue.gateway.security.ReactiveTokenBlacklistService
import com.ticketqueue.gateway.security.RouteValidator
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * JWT 토큰 검증 필터
 *
 * 처리 순서:
 * 1. 공개 엔드포인트 → 통과
 * 2. Authorization 헤더 없거나 Bearer 형식 아님 → 401
 * 3. JWT 서명/파싱 검증 → 실패 시 401 (만료: EXPIRED_TOKEN, 기타: INVALID_TOKEN)
 * 4. Redis 블랙리스트 확인 → 등록된 토큰 → 401 INVALID_TOKEN
 * 5. 관리자 전용 엔드포인트 + role != ADMIN → 403
 * 6. X-User-Id, X-User-Role 헤더 추가 후 downstream 전달
 *
 * REQ-GW-002 (JWT 검증), REQ-GW-003 (공개 엔드포인트), REQ-GW-015 (관리자 인가)
 *
 * @Order(HIGHEST_PRECEDENCE + 2): TraceIdWebFilter(HIGHEST_PRECEDENCE),
 * SecurityHeadersWebFilter(HIGHEST_PRECEDENCE + 1) 다음 실행
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class JwtAuthenticationWebFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val tokenBlacklistService: ReactiveTokenBlacklistService,
    private val routeValidator: RouteValidator,
    private val objectMapper: ObjectMapper,
) : WebFilter {

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val AUTHORIZATION_HEADER = "Authorization"
        const val USER_ID_HEADER = "X-User-Id"
        const val USER_ROLE_HEADER = "X-User-Role"

        // 에러 코드 상수 (common 모듈 의존 불가로 인라인 정의)
        private const val CODE_UNAUTHORIZED = "UNAUTHORIZED"
        private const val CODE_INVALID_TOKEN = "INVALID_TOKEN"
        private const val CODE_EXPIRED_TOKEN = "EXPIRED_TOKEN"
        private const val CODE_FORBIDDEN = "FORBIDDEN"

        private const val ROLE_ADMIN = "ADMIN"

        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // CORS preflight (OPTIONS) 요청은 JWT 검증 없이 통과
        // globalcors가 라우팅 핸들러 레벨에서 처리하므로 WebFilter가 개입하지 않아야 함
        if (exchange.request.method == HttpMethod.OPTIONS) {
            return chain.filter(exchange)
        }

        // 1. 공개 엔드포인트 통과
        if (routeValidator.isPublic(exchange)) {
            return chain.filter(exchange)
        }

        // 2. Authorization 헤더 추출
        val authHeader = exchange.request.headers.getFirst(AUTHORIZATION_HEADER)
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.debug { "Missing or invalid Authorization header: ${exchange.request.path}" }
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, CODE_UNAUTHORIZED, "인증이 필요합니다.")
        }

        val token = authHeader.removePrefix(BEARER_PREFIX)

        // 3. JWT 검증
        val claims = try {
            jwtTokenProvider.validateAndExtract(token)
        } catch (e: ExpiredJwtException) {
            log.debug { "Expired JWT token: ${exchange.request.path}" }
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, CODE_EXPIRED_TOKEN, "토큰이 만료되었습니다.")
        } catch (e: JwtException) {
            log.debug { "Invalid JWT token: ${e.message}" }
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, "유효하지 않은 토큰입니다.")
        } catch (e: IllegalArgumentException) {
            log.debug { "Invalid JWT token argument: ${e.message}" }
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, "유효하지 않은 토큰입니다.")
        }

        // 4. 블랙리스트 확인 (Redis non-blocking)
        return tokenBlacklistService.isBlacklisted(claims.jti)
            .onErrorResume { ex ->
                log.error(ex) { "Redis blacklist check failed for jti=${claims.jti}" }
                Mono.just(true)
            }
            .flatMap { isBlacklisted ->
                if (isBlacklisted) {
                    log.debug { "Blacklisted token jti=${claims.jti}" }
                    writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, "유효하지 않은 토큰입니다.")
                } else {
                    // 5. 관리자 전용 엔드포인트 인가 검사
                    if (routeValidator.isAdminOnly(exchange) && claims.role != ROLE_ADMIN) {
                        log.debug { "Forbidden: role=${claims.role} path=${exchange.request.path}" }
                        return@flatMap writeErrorResponse(exchange, HttpStatus.FORBIDDEN, CODE_FORBIDDEN, "접근 권한이 없습니다.")
                    }

                    // 6. 사용자 정보 헤더 추가 후 downstream 전달
                    val mutatedRequest = exchange.request.mutate()
                        .header(USER_ID_HEADER, claims.userId)
                        .header(USER_ROLE_HEADER, claims.role)
                        .build()

                    chain.filter(exchange.mutate().request(mutatedRequest).build())
                }
            }
    }

    private fun writeErrorResponse(
        exchange: ServerWebExchange,
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
            "timestamp" to LocalDateTime.now().format(TIMESTAMP_FORMATTER),
            "traceId" to traceId,
        )

        val bytes = objectMapper.writeValueAsBytes(body)
        val buffer = exchange.response.bufferFactory().wrap(bytes)

        exchange.response.statusCode = status
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON

        return exchange.response.writeWith(Mono.just(buffer))
    }
}
