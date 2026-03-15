package com.ticketqueue.gateway.filter

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.gateway.security.JwtTokenProvider
import com.ticketqueue.gateway.security.ReactiveTokenBlacklistService
import com.ticketqueue.gateway.security.RouteValidator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
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
    circuitBreakerRegistry: CircuitBreakerRegistry,
) : WebFilter {

    private val circuitBreaker: CircuitBreaker = circuitBreakerRegistry.circuitBreaker("redisBlacklist")

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val AUTHORIZATION_HEADER = "Authorization"
        const val USER_ID_HEADER = "X-User-Id"
        const val USER_ROLE_HEADER = "X-User-Role"

        // 허용된 role 목록 (GatewayAuthFilter와 동일한 값 유지)
        private val ALLOWED_ROLES = setOf("USER", "ADMIN")

        // 에러 코드 상수 (common 모듈 의존 불가로 인라인 정의)
        private const val CODE_UNAUTHORIZED = "UNAUTHORIZED"
        private const val CODE_INVALID_TOKEN = "INVALID_TOKEN"
        private const val CODE_EXPIRED_TOKEN = "EXPIRED_TOKEN"
        private const val CODE_FORBIDDEN = "FORBIDDEN"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // [보안] Strip-First 패턴: 외부 클라이언트가 인젝션한 신뢰 헤더를 무조건 제거.
        // JWT 검증 성공 시에만 클레임 기반으로 재추가하여 헤더 인젝션 취약점(사용자 사칭) 방지.
        val sanitizedExchange = exchange.mutate()
            .request(
                exchange.request.mutate()
                    .headers { headers ->
                        headers.remove(USER_ID_HEADER)
                        headers.remove(USER_ROLE_HEADER)
                    }
                    .build()
            )
            .build()

        // CORS preflight (OPTIONS) 요청은 JWT 검증 없이 통과
        // globalcors가 라우팅 핸들러 레벨에서 처리하므로 WebFilter가 개입하지 않아야 함
        if (exchange.request.method == HttpMethod.OPTIONS) {
            return chain.filter(sanitizedExchange)
        }

        // 1. 공개 엔드포인트 통과
        if (routeValidator.isPublic(sanitizedExchange)) {
            return chain.filter(sanitizedExchange)
        }

        // 2. Authorization 헤더 추출
        val authHeader = sanitizedExchange.request.headers.getFirst(AUTHORIZATION_HEADER)
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.debug { "Missing or invalid Authorization header: ${sanitizedExchange.request.path}" }
            return writeErrorResponse(sanitizedExchange, HttpStatus.UNAUTHORIZED, CODE_UNAUTHORIZED, "인증이 필요합니다.")
        }

        val token = authHeader.removePrefix(BEARER_PREFIX)

        // 3. JWT 검증
        val claims = try {
            jwtTokenProvider.validateAndExtract(token)
        } catch (e: ExpiredJwtException) {
            log.debug { "Expired JWT token: ${sanitizedExchange.request.path}" }
            return writeErrorResponse(sanitizedExchange, HttpStatus.UNAUTHORIZED, CODE_EXPIRED_TOKEN, "토큰이 만료되었습니다.")
        } catch (e: JwtException) {
            log.debug { "Invalid JWT token: ${e.message}" }
            return writeErrorResponse(sanitizedExchange, HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, "유효하지 않은 토큰입니다.")
        } catch (e: IllegalArgumentException) {
            log.debug { "Invalid JWT token argument: ${e.message}" }
            return writeErrorResponse(sanitizedExchange, HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, "유효하지 않은 토큰입니다.")
        }

        // 4. 블랙리스트 확인 (Redis non-blocking, CircuitBreaker 적용)
        return tokenBlacklistService.isBlacklisted(claims.jti)
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(CallNotPermittedException::class.java) { ex ->
                log.warn { "Redis blacklist circuit OPEN — fail-open for jti=${claims.jti}" }
                Mono.just(false)
            }
            .onErrorResume { ex ->
                log.error(ex) { "Redis blacklist check failed for jti=${claims.jti}" }
                Mono.just(true)
            }
            .flatMap { isBlacklisted ->
                if (isBlacklisted) {
                    log.debug { "Blacklisted token jti=${claims.jti}" }
                    writeErrorResponse(sanitizedExchange, HttpStatus.UNAUTHORIZED, CODE_INVALID_TOKEN, "유효하지 않은 토큰입니다.")
                } else {
                    // 5-1. Role 화이트리스트 검증
                    if (claims.role !in ALLOWED_ROLES) {
                        log.debug { "Invalid role: role=${claims.role} path=${sanitizedExchange.request.path}" }
                        return@flatMap writeErrorResponse(sanitizedExchange, HttpStatus.FORBIDDEN, CODE_FORBIDDEN, "접근 권한이 없습니다.")
                    }

                    // 5-2. 관리자 전용 엔드포인트 인가 검사
                    if (routeValidator.isAdminOnly(sanitizedExchange) && claims.role != "ADMIN") {
                        log.debug { "Forbidden: role=${claims.role} path=${sanitizedExchange.request.path}" }
                        return@flatMap writeErrorResponse(sanitizedExchange, HttpStatus.FORBIDDEN, CODE_FORBIDDEN, "접근 권한이 없습니다.")
                    }

                    // 6. 사용자 정보 헤더 추가 후 downstream 전달 (sanitize된 요청에 JWT 클레임만 추가)
                    val mutatedRequest = sanitizedExchange.request.mutate()
                        .headers { headers ->
                            headers.set(USER_ID_HEADER, claims.userId)
                            headers.set(USER_ROLE_HEADER, claims.role)
                        }
                        .build()

                    chain.filter(sanitizedExchange.mutate().request(mutatedRequest).build())
                }
            }
    }

    private fun writeErrorResponse(
        exchange: ServerWebExchange,
        status: HttpStatus,
        code: String,
        message: String,
    ): Mono<Void> = WebFilterErrorResponseWriter.write(exchange, objectMapper, status, code, message)
}
