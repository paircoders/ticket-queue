package com.ticketqueue.user.service

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortoneIdentityV2Response
import com.ticketqueue.common.external.portone.PortoneProperties
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.user.exception.UserException
import io.github.oshai.kotlinlogging.KotlinLogging
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
    fun verifyIdentity(identityVerificationId: String): PortoneIdentityV2Response.VerifiedCustomerDetail {
        val response = try {
            val token = portoneTokenService.getAccessToken()
            portoneClient.getIdentityVerification(
                identityVerificationId = identityVerificationId,
                storeId = portoneProperties.storeId,
                token = token
            )
        } catch (e: BusinessException) {
            when {
                e.errorCode.status.value() == 404 -> throw UserException(ErrorCode.PORTONE_VERIFICATION_NOT_FOUND)
                e.errorCode.status.value() in 400..499 -> throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
                else -> throw UserException(ErrorCode.PORTONE_API_ERROR)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error while getting identity verification" }
            throw UserException(ErrorCode.PORTONE_API_ERROR)
        }

        return when (response.status) {
            "VERIFIED" -> response.verifiedCustomer ?: throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
            "READY" -> throw UserException(ErrorCode.PORTONE_VERIFICATION_TIMEOUT)
            "FAILED" -> throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
            else -> throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
        }
    }
}
