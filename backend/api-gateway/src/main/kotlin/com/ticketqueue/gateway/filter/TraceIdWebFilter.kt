package com.ticketqueue.gateway.filter

import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * API Gateway 진입점에서 traceId 생성 및 전파
 *
 * 역할:
 * 1. 요청 헤더에서 X-Trace-Id 확인 → 있으면 사용, 없으면 UUID 생성
 * 2. 응답 헤더에 X-Trace-Id 설정
 * 3. Downstream 서비스로 X-Trace-Id 헤더 전파
 * 4. Reactor Context 및 MDC에 traceId 저장 (MdcContextPropagationConfig와 연동)
 *
 * REQ-GW-009 (Request ID 전파, 필수 요구사항) 준수
 *
 * WebFilter 사용 이유:
 * - GlobalFilter보다 먼저 실행되어 라우팅 전에 모든 경로(/internal 포함)에 적용
 * - HIGHEST_PRECEDENCE로 다른 모든 필터보다 우선 실행
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdWebFilter : WebFilter {

    companion object {
        const val TRACE_ID_HEADER = "X-Trace-Id"
        const val TRACE_ID_MDC_KEY = "traceId"
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val incomingTraceId = exchange.request.headers.getFirst(TRACE_ID_HEADER)
        val traceId = if (!incomingTraceId.isNullOrBlank()) {
            incomingTraceId
        } else {
            UUID.randomUUID().toString()
        }

        exchange.response.headers.set(TRACE_ID_HEADER, traceId)

        val mutatedRequest = exchange.request.mutate()
            .header(TRACE_ID_HEADER, traceId)
            .build()

        val mutatedExchange = exchange.mutate()
            .request(mutatedRequest)
            .build()

        return chain.filter(mutatedExchange)
            .contextWrite { context ->
                context.put(TRACE_ID_MDC_KEY, traceId)
            }
            .doOnEach { signal ->
                if (!signal.isOnError) {
                    signal.contextView.getOrDefault<String>(TRACE_ID_MDC_KEY, null)?.let {
                        MDC.put(TRACE_ID_MDC_KEY, it)
                    }
                }
            }
            .doFinally {
                MDC.remove(TRACE_ID_MDC_KEY)
            }
    }
}
