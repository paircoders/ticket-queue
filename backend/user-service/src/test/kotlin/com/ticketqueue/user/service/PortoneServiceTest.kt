package com.ticketqueue.user.service

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.ExternalSystemException
import com.ticketqueue.common.external.portone.*
import com.ticketqueue.user.exception.UserException
import feign.Request
import feign.RetryableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PortoneServiceTest {

    private lateinit var portoneClient: PortoneFeignClient
    private lateinit var portoneTokenService: PortoneTokenService
    private lateinit var portoneProperties: PortoneProperties
    private lateinit var portoneService: PortoneService

    @BeforeEach
    fun setUp() {
        portoneClient = mockk()
        portoneTokenService = mockk()
        portoneProperties = PortoneProperties(
            apiUrl = "https://api.portone.io",
            apiSecret = "test-secret",
            storeId = "test-store-id"
        )
        portoneService = PortoneService(portoneClient, portoneTokenService, portoneProperties)
    }

    @Test
    fun `본인인증 성공 - VERIFIED 상태이면 인증 정보를 반환한다`() {
        // given
        val identityVerificationId = "test-id"
        val accessToken = "Bearer test-token"
        val verifiedCustomer = PortoneIdentityV2Response.VerifiedCustomerDetail(
            name = "홍길동",
            ci = "test-ci",
            di = "test-di"
        )
        val response = PortoneIdentityV2Response(
            id = identityVerificationId,
            status = "VERIFIED",
            verifiedCustomer = verifiedCustomer
        )

        every { portoneTokenService.getAccessToken() } returns accessToken
        every {
            portoneClient.getIdentityVerification(identityVerificationId, "test-store-id", accessToken)
        } returns response

        // when
        val result = portoneService.verifyIdentity(identityVerificationId)

        // then
        result.name shouldBe "홍길동"
        result.ci shouldBe "test-ci"
        result.di shouldBe "test-di"
    }

    @Test
    fun `본인인증 실패 - READY 상태이면 PORTONE_VERIFICATION_TIMEOUT 예외를 던진다`() {
        // given
        val identityVerificationId = "ready-id"
        val accessToken = "Bearer test-token"
        val response = PortoneIdentityV2Response(
            id = identityVerificationId,
            status = "READY",
            verifiedCustomer = null
        )

        every { portoneTokenService.getAccessToken() } returns accessToken
        every {
            portoneClient.getIdentityVerification(identityVerificationId, "test-store-id", accessToken)
        } returns response

        // when & then
        val exception = shouldThrow<UserException> {
            portoneService.verifyIdentity(identityVerificationId)
        }
        exception.errorCode shouldBe ErrorCode.PORTONE_VERIFICATION_TIMEOUT
    }

    @Test
    fun `본인인증 실패 - 404 에러 발생 시 PORTONE_VERIFICATION_NOT_FOUND 예외를 던진다`() {
        // given
        val identityVerificationId = "invalid-id"
        val accessToken = "Bearer test-token"
        
        every { portoneTokenService.getAccessToken() } returns accessToken
        val businessException = mockk<BusinessException>()
        every { businessException.errorCode.status.value() } returns 404
        
        every {
            portoneClient.getIdentityVerification(any(), any(), any())
        } throws businessException

        // when & then
        val exception = shouldThrow<UserException> {
            portoneService.verifyIdentity(identityVerificationId)
        }
        exception.errorCode shouldBe ErrorCode.PORTONE_VERIFICATION_NOT_FOUND
    }

    @Test
    fun `본인인증 실패 - 4xx 에러 발생 시 PORTONE_VERIFICATION_FAILED 예외를 던진다`() {
        // given
        val identityVerificationId = "bad-request-id"
        val accessToken = "Bearer test-token"
        
        every { portoneTokenService.getAccessToken() } returns accessToken
        val businessException = mockk<BusinessException>()
        every { businessException.errorCode.status.value() } returns 400
        
        every {
            portoneClient.getIdentityVerification(any(), any(), any())
        } throws businessException

        // when & then
        val exception = shouldThrow<UserException> {
            portoneService.verifyIdentity(identityVerificationId)
        }
        exception.errorCode shouldBe ErrorCode.PORTONE_VERIFICATION_FAILED
    }

    @Test
    fun `본인인증 실패 - 5xx 에러 발생 시 PORTONE_API_ERROR 예외를 던진다`() {
        // given
        val identityVerificationId = "server-error-id"
        val accessToken = "Bearer test-token"
        
        every { portoneTokenService.getAccessToken() } returns accessToken
        val retryableException = RetryableException(
            500,
            "Internal Server Error",
            Request.HttpMethod.GET,
            0L,
            mockk<Request>(relaxed = true) // Request는 복잡하므로 relaxed mock 사용
        )

        every {
            portoneClient.getIdentityVerification(any(), any(), any())
        } throws retryableException

        // when & then
        val exception = shouldThrow<ExternalSystemException> {
            portoneService.verifyIdentity(identityVerificationId)
        }
        exception.errorCode shouldBe ErrorCode.PORTONE_API_ERROR
    }
}
