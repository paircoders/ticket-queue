package com.ticketqueue.common.external.portone

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Issue #61 — PortoneFallbackFactory 매핑 계약을 잠그는 단위 테스트.
 *
 * Resilience4j CircuitBreaker 가 호출을 차단해 던지는 [CallNotPermittedException] 만
 * 도메인 예외 [PortoneCircuitOpenException] 으로 변환되어야 한다. 그 외 cause 는 원본 그대로
 * 재전파되어 호출자의 기존 FeignException catch 흐름이 깨지지 않는다.
 */
@DisplayName("PortoneFallbackFactory 매핑 단위 테스트")
class PortoneFallbackFactoryTest {

    private val factory = PortoneFallbackFactory()

    private fun openCircuitException(): CallNotPermittedException {
        val cb = CircuitBreaker.ofDefaults("portone-test")
        cb.transitionToOpenState()
        return CallNotPermittedException.createCallNotPermittedException(cb)
    }

    @Test
    @DisplayName("CallNotPermittedException 원인 시 preRegisterPayment 는 PortoneCircuitOpenException(cause=원본) 으로 변환된다")
    fun preRegisterMapsCircuitOpen() {
        val cause = openCircuitException()
        val fallback = factory.create(cause)

        val ex = shouldThrow<PortoneCircuitOpenException> {
            fallback.preRegisterPayment(
                paymentId = "pk-test",
                request = PortonePreRegisterRequest(storeId = "store", totalAmount = 1000L),
                token = "Bearer t",
            )
        }
        ex.cause shouldBe cause
    }

    @Test
    @DisplayName("CallNotPermittedException 원인 시 getPayment 도 동일 매핑")
    fun getPaymentMapsCircuitOpen() {
        val cause = openCircuitException()
        val fallback = factory.create(cause)

        shouldThrow<PortoneCircuitOpenException> {
            fallback.getPayment(paymentId = "pk-test", storeId = "store", token = "Bearer t")
        }
    }

    @Test
    @DisplayName("CallNotPermittedException 원인 시 토큰 발급 메서드(login) 도 동일 매핑")
    fun loginMapsCircuitOpen() {
        val cause = openCircuitException()
        val fallback = factory.create(cause)

        shouldThrow<PortoneCircuitOpenException> {
            fallback.login(PortoneTokenRequest(apiSecret = "secret"))
        }
    }

    @Test
    @DisplayName("일반 RuntimeException 원인은 원본 그대로 재전파된다 (FeignException catch 흐름 보존)")
    fun rethrowsRuntimeCauseAsIs() {
        val cause = RuntimeException("connection refused")
        val fallback = factory.create(cause)

        val ex = shouldThrow<RuntimeException> {
            fallback.getPayment(paymentId = "pk-test", storeId = "store", token = "Bearer t")
        }
        ex shouldBe cause
    }
}
