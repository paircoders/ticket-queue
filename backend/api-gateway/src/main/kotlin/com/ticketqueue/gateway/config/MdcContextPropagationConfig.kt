package com.ticketqueue.gateway.config

import io.micrometer.context.ContextRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Hooks

/**
 * Reactor Context ↔ MDC 자동 동기화 설정
 *
 * WebFlux는 Reactor 스레드 풀에서 스레드 전환이 빈번하므로, ThreadLocal 기반 MDC가 유실됨.
 * context-propagation이 매 operator 경계에서 Reactor Context → MDC를 자동 복원.
 *
 * REQ-GW-009 (Request ID 전파) 준수를 위해 필수 설정.
 */
@Configuration
class MdcContextPropagationConfig {

    @PostConstruct
    fun setupContextPropagation() {
        // traceId를 Reactor Context ↔ MDC 간 전파 대상으로 등록
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            "traceId",
            { MDC.get("traceId") },            // MDC에서 읽기
            { value -> MDC.put("traceId", value) },  // MDC에 쓰기
            { MDC.remove("traceId") }          // MDC에서 제거
        )

        // Reactor 전역 훅 활성화 (모든 Operator에서 Context 전파)
        Hooks.enableAutomaticContextPropagation()
    }
}
