package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.ExternalSystemException
import com.ticketqueue.common.external.portone.PortoneIdentityV2Response
import com.ticketqueue.user.config.JwtProperties
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.exception.UserException
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class AuthServiceTest {

    private lateinit var recaptchaService: RecaptchaService
    private lateinit var encryptionService: EncryptionService
    private lateinit var portoneService: PortoneService
    private lateinit var authTransactionalService: AuthTransactionalService
    private lateinit var loginHistoryRecorder: LoginHistoryRecorder
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var tokenBlacklistService: TokenBlacklistService
    private lateinit var jwtProperties: JwtProperties
    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        recaptchaService = mockk()
        encryptionService = mockk()
        portoneService = mockk()
        authTransactionalService = mockk()
        loginHistoryRecorder = mockk(relaxed = true)
        jwtTokenProvider = mockk()
        tokenBlacklistService = mockk(relaxed = true)
        jwtProperties = mockk()
        authService = AuthService(recaptchaService, portoneService, authTransactionalService, loginHistoryRecorder, encryptionService, jwtTokenProvider, tokenBlacklistService, jwtProperties)
    }

    // ─── 공통 헬퍼 ────────────────────────────────────────────────────────────────

    private fun createSignupRequest(
        email: String = "test@example.com",
        password: String = "password123!",
        name: String = "홍길동",
        phone: String = "01012345678",
        identityVerificationId: String = "test-verification-id",
        recaptchaToken: String = "valid-recaptcha-token"
    ) = AuthDto.SignupRequest(
        email = email,
        password = password,
        name = name,
        phone = phone,
        identityVerificationId = identityVerificationId,
        recaptchaToken = recaptchaToken
    )

    private fun createVerifiedCustomer(
        name: String = "홍길동",
        phone: String = "01012345678",
        ci: String? = "test-ci-value",
        di: String? = "test-di-value"
    ) = PortoneIdentityV2Response.VerifiedCustomerDetail(
        name = name,
        phoneNumber = phone,
        ci = ci,
        di = di
    )

    private fun createLoginRequest(
        email: String = "test@example.com",
        password: String = "password123!",
        recaptchaToken: String = "valid-recaptcha-token"
    ) = AuthDto.LoginRequest(
        email = email,
        password = password,
        recaptchaToken = recaptchaToken
    )

    // ─── Signup 테스트 ────────────────────────────────────────────────────────────

    @Test
    fun `회원가입 성공 - reCAPTCHA, PortOne 후 processSignup 위임`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer()
        val userId = UUID.randomUUID()
        val expectedResponse = AuthDto.SignupResponse(id = userId, email = request.email, name = request.name)

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { portoneService.verifyIdentity(request.identityVerificationId) } returns verifiedCustomer
        every { authTransactionalService.processSignup(request, verifiedCustomer.ci!!, verifiedCustomer.di!!) } returns expectedResponse

        // when
        val response = authService.signup(request)

        // then
        response.id shouldBe userId
        response.email shouldBe request.email
        response.name shouldBe request.name
        verify(exactly = 1) { authTransactionalService.processSignup(request, verifiedCustomer.ci!!, verifiedCustomer.di!!) }
    }

    @Test
    fun `reCAPTCHA 실패 - RECAPTCHA_FAILED 예외, processSignup 미호출`() {
        // given
        val request = createSignupRequest()
        every { recaptchaService.verify(request.recaptchaToken) } returns false

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        exception.errorCode shouldBe ErrorCode.RECAPTCHA_FAILED
        verify(exactly = 0) { portoneService.verifyIdentity(any()) }
        verify(exactly = 0) { authTransactionalService.processSignup(any(), any(), any()) }
        verify(exactly = 0) { loginHistoryRecorder.recordFailureWithoutUser(any(), any(), any()) }
    }

    @Test
    fun `PortOne 본인인증 정보에 CI가 없는 경우 - PORTONE_MISSING_REQUIRED_INFO 예외`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer(ci = null)

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { portoneService.verifyIdentity(request.identityVerificationId) } returns verifiedCustomer

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        exception.errorCode shouldBe ErrorCode.PORTONE_MISSING_REQUIRED_INFO
        verify(exactly = 0) { authTransactionalService.processSignup(any(), any(), any()) }
    }

    @Test
    fun `PortOne 본인인증 정보에 DI가 없는 경우 - PORTONE_MISSING_REQUIRED_INFO 예외`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer(di = null)

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { portoneService.verifyIdentity(request.identityVerificationId) } returns verifiedCustomer

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        exception.errorCode shouldBe ErrorCode.PORTONE_MISSING_REQUIRED_INFO
        verify(exactly = 0) { authTransactionalService.processSignup(any(), any(), any()) }
    }

    @Test
    fun `reCAPTCHA가 PortOne 호출보다 먼저 수행`() {
        // given
        val request = createSignupRequest()
        val callOrder = mutableListOf<String>()

        every { recaptchaService.verify(request.recaptchaToken) } answers {
            callOrder.add("recaptcha")
            false
        }
        every { portoneService.verifyIdentity(any()) } answers {
            callOrder.add("portone")
            createVerifiedCustomer()
        }

        // when & then
        assertThrows<UserException> { authService.signup(request) }

        callOrder shouldBe listOf("recaptcha")
        verify(exactly = 0) { portoneService.verifyIdentity(any()) }
    }

    @Test
    fun `PortOne 호출이 processSignup보다 먼저 수행`() {
        // given
        val request = createSignupRequest()
        val callOrder = mutableListOf<String>()

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { portoneService.verifyIdentity(request.identityVerificationId) } answers {
            callOrder.add("portone")
            throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
        }
        every { authTransactionalService.processSignup(any(), any(), any()) } answers {
            callOrder.add("processSignup")
            mockk()
        }

        // when & then
        assertThrows<UserException> { authService.signup(request) }

        callOrder shouldBe listOf("portone")
        verify(exactly = 0) { authTransactionalService.processSignup(any(), any(), any()) }
    }

    // ─── Login 테스트 ─────────────────────────────────────────────────────────────

    @Nested
    inner class LoginTest {

        @Test
        fun `정상 로그인 - processLogin 위임 후 LoginResponse 반환`() {
            // given
            val request = createLoginRequest()
            val expectedResponse = AuthDto.LoginResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresIn = 3600L,
            )

            every { recaptchaService.verify(request.recaptchaToken) } returns true
            every { encryptionService.hash(request.email) } returns "email-hash"
            every {
                authTransactionalService.processLogin("email-hash", request.password, "", "")
            } returns expectedResponse

            // when
            val response = authService.login(request)

            // then
            response.accessToken shouldBe "access-token"
            response.refreshToken shouldBe "refresh-token"
            response.expiresIn shouldBe 3600L
            verify(exactly = 1) {
                authTransactionalService.processLogin("email-hash", request.password, "", "")
            }
            verify(exactly = 0) { loginHistoryRecorder.recordFailureWithoutUser(any(), any(), any()) }
        }

        @Test
        fun `정상 로그인 - 이메일 해시를 processLogin에 전달`() {
            // given
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } returns true
            every { encryptionService.hash(request.email) } returns "computed-email-hash"
            every {
                authTransactionalService.processLogin("computed-email-hash", any(), any(), any())
            } returns mockk(relaxed = true)

            // when
            authService.login(request)

            // then
            verify { encryptionService.hash(request.email) }
            verify { authTransactionalService.processLogin("computed-email-hash", any(), any(), any()) }
        }

        @Test
        fun `reCAPTCHA 서비스 장애 - ExternalSystemException 전파 및 RECAPTCHA_SERVICE_ERROR 이력 기록`() {
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } throws
                ExternalSystemException(ErrorCode.RECAPTCHA_SERVICE_ERROR)

            assertThrows<ExternalSystemException> {
                authService.login(request, "127.0.0.1", "TestAgent")
            }

            verify(exactly = 1) {
                loginHistoryRecorder.recordFailureWithoutUser("127.0.0.1", "TestAgent", "RECAPTCHA_SERVICE_ERROR")
            }
            verify(exactly = 0) { authTransactionalService.processLogin(any(), any(), any(), any()) }
        }

        @Test
        fun `reCAPTCHA 실패 - RECAPTCHA_FAILED 예외, processLogin 미호출`() {
            // given
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } returns false

            // when & then
            val exception = assertThrows<UserException> {
                authService.login(request, "127.0.0.1", "TestAgent")
            }

            exception.errorCode shouldBe ErrorCode.RECAPTCHA_FAILED
            verify(exactly = 0) { encryptionService.hash(any()) }
            verify(exactly = 0) { authTransactionalService.processLogin(any(), any(), any(), any()) }
            verify(exactly = 1) {
                loginHistoryRecorder.recordFailureWithoutUser("127.0.0.1", "TestAgent", "RECAPTCHA_FAILED")
            }
        }

        @Test
        fun `processLogin 예외 - 그대로 전파`() {
            // given
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } returns true
            every { encryptionService.hash(request.email) } returns "email-hash"
            every {
                authTransactionalService.processLogin(any(), any(), any(), any())
            } throws UserException(ErrorCode.INVALID_CREDENTIALS)

            // when & then
            val exception = assertThrows<UserException> {
                authService.login(request)
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
        }
    }

    // ─── Refresh 테스트 ───────────────────────────────────────────────────────────

    @Nested
    inner class RefreshTest {

        private val validRequest = AuthDto.RefreshRequest(refreshToken = "valid-refresh-token")
        private val validResponse = AuthDto.LoginResponse(
            accessToken = "new-access-token",
            refreshToken = "new-refresh-token",
            expiresIn = 3600L,
        )

        @Test
        fun refresh_shouldReturnLoginResponse_whenValid() {
            // given
            every { jwtTokenProvider.validateAndParseRefreshToken(validRequest.refreshToken) } just Runs
            every { authTransactionalService.processRefresh(validRequest.refreshToken) } returns validResponse

            // when
            val response = authService.refresh(validRequest)

            // then
            response.accessToken shouldBe "new-access-token"
            response.refreshToken shouldBe "new-refresh-token"
            verify(exactly = 1) { jwtTokenProvider.validateAndParseRefreshToken(validRequest.refreshToken) }
            verify(exactly = 1) { authTransactionalService.processRefresh(validRequest.refreshToken) }
        }

        @Test
        fun refresh_shouldThrowInvalidToken_whenSignatureInvalid() {
            // given
            every { jwtTokenProvider.validateAndParseRefreshToken(any()) } throws UserException(ErrorCode.INVALID_TOKEN)

            // when & then
            val exception = assertThrows<UserException> {
                authService.refresh(validRequest)
            }

            exception.errorCode shouldBe ErrorCode.INVALID_TOKEN
            verify(exactly = 0) { authTransactionalService.processRefresh(any()) }
        }

        @Test
        fun refresh_shouldThrowExpiredToken_whenExpired() {
            // given
            every { jwtTokenProvider.validateAndParseRefreshToken(any()) } throws UserException(ErrorCode.EXPIRED_TOKEN)

            // when & then
            val exception = assertThrows<UserException> {
                authService.refresh(validRequest)
            }

            exception.errorCode shouldBe ErrorCode.EXPIRED_TOKEN
            verify(exactly = 0) { authTransactionalService.processRefresh(any()) }
        }

        @Test
        fun refresh_shouldPropagateException_whenProcessRefreshFails() {
            // given
            every { jwtTokenProvider.validateAndParseRefreshToken(any()) } just Runs
            every { authTransactionalService.processRefresh(any()) } throws UserException(ErrorCode.REVOKED_REFRESH_TOKEN)

            // when & then
            val exception = assertThrows<UserException> {
                authService.refresh(validRequest)
            }

            exception.errorCode shouldBe ErrorCode.REVOKED_REFRESH_TOKEN
        }
    }

    // ─── Logout 테스트 ────────────────────────────────────────────────────────────

    @Nested
    inner class LogoutTest {

        private val jti = "test-jti-value"
        private val accessToken = "valid.access.token"
        private val validHeader = "Bearer $accessToken"

        @Test
        fun `정상 로그아웃 - DB revoke 먼저, Redis 블랙리스트 나중 순서 검증`() {
            // given
            every { jwtTokenProvider.parseAccessTokenJti(accessToken) } returns jti
            every { authTransactionalService.processLogout(jti) } just Runs
            every { jwtProperties.accessTokenExpiry } returns 3600000L
            every { tokenBlacklistService.addToBlacklist(jti, 3600000L) } just Runs

            // when
            authService.logout(validHeader)

            // then
            verifyOrder {
                authTransactionalService.processLogout(jti)
                tokenBlacklistService.addToBlacklist(jti, 3600000L)
            }
        }

        @Test
        fun `Authorization 헤더 없음 - UNAUTHORIZED 예외, DB revoke 및 블랙리스트 미호출`() {
            // when & then
            val exception = assertThrows<UserException> {
                authService.logout("")
            }

            exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
            verify(exactly = 0) { jwtTokenProvider.parseAccessTokenJti(any()) }
            verify(exactly = 0) { authTransactionalService.processLogout(any()) }
            verify(exactly = 0) { tokenBlacklistService.addToBlacklist(any(), any()) }
        }

        @Test
        fun `Bearer 접두사 없음 - UNAUTHORIZED 예외, DB revoke 및 블랙리스트 미호출`() {
            // when & then
            val exception = assertThrows<UserException> {
                authService.logout(accessToken)
            }

            exception.errorCode shouldBe ErrorCode.UNAUTHORIZED
            verify(exactly = 0) { jwtTokenProvider.parseAccessTokenJti(any()) }
            verify(exactly = 0) { authTransactionalService.processLogout(any()) }
            verify(exactly = 0) { tokenBlacklistService.addToBlacklist(any(), any()) }
        }

        @Test
        fun `유효하지 않은 토큰 - INVALID_TOKEN 전파, DB revoke 및 블랙리스트 미호출`() {
            // given
            every { jwtTokenProvider.parseAccessTokenJti(accessToken) } throws UserException(ErrorCode.INVALID_TOKEN)

            // when & then
            val exception = assertThrows<UserException> {
                authService.logout(validHeader)
            }

            exception.errorCode shouldBe ErrorCode.INVALID_TOKEN
            verify(exactly = 0) { authTransactionalService.processLogout(any()) }
            verify(exactly = 0) { tokenBlacklistService.addToBlacklist(any(), any()) }
        }
    }
}
