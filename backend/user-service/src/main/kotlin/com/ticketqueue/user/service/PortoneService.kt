package com.ticketqueue.user.service

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.ExternalSystemException
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortoneIdentityV2Response
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.user.exception.UserException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.stereotype.Service

@Service
class PortoneService(
    private val portoneClient: PortoneFeignClient,
    private val portoneTokenService: PortoneTokenService,
    private val portoneProperties: PortoneProperties
) {

    private val logger = KotlinLogging.logger {}

    /**
     * PortOne 본인인증 정보를 조회하고 검증된 정보를 반환
     */
    @CircuitBreaker(name = "portone-v2-client", fallbackMethod = "verifyIdentityFallback")
    fun verifyIdentity(identityVerificationId: String): PortoneIdentityV2Response.VerifiedCustomerDetail {
        val response = try {
            val token = portoneTokenService.getAccessToken()
            portoneClient.getIdentityVerification(
                identityVerificationId = identityVerificationId,
                storeId = portoneProperties.storeId,
                token = token
            )
        } catch (e: BusinessException) {
            // 4xx 비즈니스 오류 — CB 무시
            when {
                e.errorCode.status.value() == 404 -> throw UserException(ErrorCode.PORTONE_VERIFICATION_NOT_FOUND)
                e.errorCode.status.value() in 400..499 -> throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
                else -> throw ExternalSystemException(ErrorCode.PORTONE_API_ERROR, cause = e)  // 5xx
            }
        } catch (e: Exception) {
            // 네트워크/타임아웃 — CB 카운트
            logger.error(e) { "External system error from PortOne" }
            throw ExternalSystemException(ErrorCode.PORTONE_API_ERROR, cause = e)
        }

        return when (response.status) {
            "VERIFIED" -> response.verifiedCustomer ?: throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
            "READY" -> throw UserException(ErrorCode.PORTONE_VERIFICATION_TIMEOUT)
            "FAILED" -> throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
            else -> throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
        }
    }

    private fun verifyIdentityFallback(
        identityVerificationId: String,
        ex: Throwable,
    ): PortoneIdentityV2Response.VerifiedCustomerDetail {

        // 비즈니스 예외인 경우 그대로 예외 던짐
        if(ex is BusinessException) {
            throw ex
        }

        // 그 외 시스템 장애인 경우 폴백 로직 수행
        logger.error { "PortOne circuit breaker triggered: ${ex.message}" }
        throw ExternalSystemException(ErrorCode.PORTONE_API_ERROR)
    }
}
