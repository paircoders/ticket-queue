package com.ticketqueue.common.external.portone

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

/**
 * PortOne FeignClient 호출이 Resilience4j Circuit Breaker 또는 일반 예외로 실패했을 때
 * 도메인 예외로 변환하는 FallbackFactory.
 *
 * - CallNotPermittedException → PortoneCircuitOpenException (503) 로 변환하여 호출자에게 CB Open 의미 전달
 * - 그 외 cause → 원본을 그대로 재전파하여 기존 catch 흐름(FeignException 등) 유지
 */
@Component
class PortoneFallbackFactory : FallbackFactory<PortoneFeignClient> {

    override fun create(cause: Throwable): PortoneFeignClient = PortoneFallback(cause)

    private class PortoneFallback(private val cause: Throwable) : PortoneFeignClient {

        override fun login(request: PortoneTokenRequest): PortoneTokenResponse = throwMapped()

        override fun refreshToken(request: PortoneRefreshRequest): PortoneTokenResponse = throwMapped()

        override fun getIdentityVerification(
            identityVerificationId: String,
            storeId: String?,
            token: String
        ): PortoneIdentityV2Response = throwMapped()

        override fun preRegisterPayment(
            paymentId: String,
            request: PortonePreRegisterRequest,
            token: String
        ): Unit = throwMapped()

        override fun getPayment(
            paymentId: String,
            storeId: String?,
            token: String
        ): PortonePaymentResponse = throwMapped()

        private fun throwMapped(): Nothing {
            if (cause is CallNotPermittedException) {
                throw PortoneCircuitOpenException(cause)
            }
            throw cause
        }
    }
}
