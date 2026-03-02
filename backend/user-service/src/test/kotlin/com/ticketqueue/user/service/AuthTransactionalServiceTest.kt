package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.config.JwtProperties
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.entity.RefreshToken
import com.ticketqueue.user.entity.User
import com.ticketqueue.user.entity.UserRole
import com.ticketqueue.user.entity.UserStatus
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.repository.RefreshTokenRepository
import com.ticketqueue.user.repository.UserRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class AuthTransactionalServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var encryptionService: EncryptionService
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var refreshTokenRepository: RefreshTokenRepository
    private lateinit var loginHistoryRecorder: LoginHistoryRecorder
    private lateinit var authTransactionalService: AuthTransactionalService

    private val jwtProperties = JwtProperties(
        secret = Base64.getEncoder().encodeToString("test-secret-key-at-least-32-bytes-long!!".toByteArray()),
        accessTokenExpiry = 3600000L,
        refreshTokenExpiry = 604800000L,
    )

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        passwordEncoder = mockk()
        encryptionService = mockk()
        jwtTokenProvider = mockk()
        refreshTokenRepository = mockk()
        loginHistoryRecorder = mockk()
        authTransactionalService = AuthTransactionalService(
            userRepository, passwordEncoder, encryptionService, jwtTokenProvider,
            refreshTokenRepository, loginHistoryRecorder, jwtProperties
        )
    }

    // ─── 공통 헬퍼 ────────────────────────────────────────────────────────────────

    private fun createActiveUser(
        id: UUID = UUID.randomUUID(),
        status: UserStatus = UserStatus.ACTIVE,
        role: UserRole = UserRole.USER,
    ) = User(
        id = id,
        email = "encrypted-email",
        emailHash = "email-hash",
        passwordHash = "bcrypt-hash",
        name = "encrypted-name",
        phone = "encrypted-phone",
        phoneHash = "phone-hash",
        status = status,
        role = role,
    )

    private fun mockSuccessfulLogin(user: User, email: String = "test@example.com") {
        every { passwordEncoder.matches(any(), user.passwordHash) } returns true
        every { encryptionService.decrypt(user.email) } returns email
        every { jwtTokenProvider.generateAccessToken(user.id!!, user.role, email) } returns Pair("access-token", "access-jti")
        every { jwtTokenProvider.generateRefreshToken(user.id!!) } returns Pair("refresh-token", "refresh-jti")
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { loginHistoryRecorder.recordSuccess(any(), any(), any()) } just Runs
    }

    private fun createSignupRequest(
        email: String = "test@example.com",
        password: String = "password123!",
        name: String = "홍길동",
        phone: String = "01012345678",
    ) = AuthDto.SignupRequest(
        email = email,
        password = password,
        name = name,
        phone = phone,
        identityVerificationId = "test-verification-id",
        recaptchaToken = "valid-recaptcha-token"
    )

    // ─── processLogin 테스트 ──────────────────────────────────────────────────────

    @Nested
    inner class ProcessLoginTest {

        @Test
        fun `정상 로그인 - LoginResponse 반환`() {
            // given
            val userId = UUID.randomUUID()
            val user = createActiveUser(id = userId)
            every { userRepository.findByEmailHash("email-hash") } returns user
            mockSuccessfulLogin(user)

            // when
            val response = authTransactionalService.processLogin(
                emailHash = "email-hash", password = "password", ipAddress = "127.0.0.1",
                userAgent = "Mozilla/5.0"
            )

            // then
            response.accessToken shouldBe "access-token"
            response.refreshToken shouldBe "refresh-token"
            response.expiresIn shouldBe 3600L
            response.tokenType shouldBe "Bearer"
        }

        @Test
        fun `정상 로그인 - RefreshToken DB 저장`() {
            // given
            val user = createActiveUser()
            every { userRepository.findByEmailHash("email-hash") } returns user
            mockSuccessfulLogin(user)

            // when
            authTransactionalService.processLogin(
                emailHash = "email-hash", password = "password", ipAddress = "",
                userAgent = ""
            )

            // then
            verify(exactly = 1) { refreshTokenRepository.save(any()) }
        }

        @Test
        fun `존재하지 않는 이메일 - recordFailureWithoutUser 호출 및 INVALID_CREDENTIALS 예외`() {
            // given
            every { userRepository.findByEmailHash("not-found-hash") } returns null
            every { loginHistoryRecorder.recordFailureWithoutUser("1.2.3.4", "Chrome", "USER_NOT_FOUND") } just Runs

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processLogin(
                    emailHash = "not-found-hash", password = "any", ipAddress = "1.2.3.4",
                    userAgent = "Chrome"
                )
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            verify(exactly = 1) { loginHistoryRecorder.recordFailureWithoutUser("1.2.3.4", "Chrome", "USER_NOT_FOUND") }
            verify(exactly = 0) { loginHistoryRecorder.recordFailure(any(), any(), any(), any()) }
        }

        @Test
        fun `DELETED 계정 로그인 - INVALID_CREDENTIALS 예외 및 ACCOUNT_DELETED 이력 기록`() {
            // given
            val user = createActiveUser(status = UserStatus.DELETED)
            every { userRepository.findByEmailHash("email-hash") } returns user
            every { loginHistoryRecorder.recordFailure(user, any(), any(), "ACCOUNT_DELETED") } just Runs

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processLogin(
                    emailHash = "email-hash", password = "pw", ipAddress = "", userAgent = ""
                )
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            verify(exactly = 1) { loginHistoryRecorder.recordFailure(user, any(), any(), "ACCOUNT_DELETED") }
            verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
        }

        @Test
        fun `DORMANT 계정 로그인 - INVALID_CREDENTIALS 예외 및 ACCOUNT_DORMANT 이력 기록`() {
            // given
            val user = createActiveUser(status = UserStatus.DORMANT)
            every { userRepository.findByEmailHash("email-hash") } returns user
            every { loginHistoryRecorder.recordFailure(user, any(), any(), "ACCOUNT_DORMANT") } just Runs

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processLogin(
                    emailHash = "email-hash", password = "pw", ipAddress = "", userAgent = ""
                )
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            verify(exactly = 1) { loginHistoryRecorder.recordFailure(user, any(), any(), "ACCOUNT_DORMANT") }
            verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
        }

        @Test
        fun `비밀번호 불일치 - INVALID_CREDENTIALS 예외 및 INVALID_PASSWORD 이력 기록`() {
            // given
            val user = createActiveUser()
            every { userRepository.findByEmailHash("email-hash") } returns user
            every { passwordEncoder.matches("wrong", user.passwordHash) } returns false
            every { loginHistoryRecorder.recordFailure(user, any(), any(), "INVALID_PASSWORD") } just Runs

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processLogin(
                    emailHash = "email-hash", password = "wrong", ipAddress = "", userAgent = ""
                )
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            verify(exactly = 1) { loginHistoryRecorder.recordFailure(user, any(), any(), "INVALID_PASSWORD") }
        }

        @Test
        fun `JWT secret 설정 오류 시 JWT_CONFIGURATION_ERROR 예외 및 실패 이력 기록`() {
            // given
            val user = createActiveUser()
            every { userRepository.findByEmailHash("email-hash") } returns user
            every { passwordEncoder.matches(any(), user.passwordHash) } returns true
            every { encryptionService.decrypt(user.email) } returns "test@example.com"
            every { jwtTokenProvider.generateAccessToken(user.id!!, user.role, any()) } throws
                IllegalStateException("JWT secret은 유효한 Base64 형식이어야 합니다.")
            every { loginHistoryRecorder.recordFailure(user, any(), any(), "JWT_CONFIG_ERROR") } just Runs

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processLogin(
                    emailHash = "email-hash", password = "pw", ipAddress = "", userAgent = ""
                )
            }

            exception.errorCode shouldBe ErrorCode.JWT_CONFIGURATION_ERROR
            verify(exactly = 1) { loginHistoryRecorder.recordFailure(user, any(), any(), eq("JWT_CONFIG_ERROR")) }
        }
    }

    // ─── processSignup 테스트 ─────────────────────────────────────────────────────

    @Nested
    inner class ProcessSignupTest {

        private fun mockSignupDependencies(
            request: AuthDto.SignupRequest,
            verifiedCi: String = "test-ci-value",
            verifiedDi: String = "test-di-value",
            emailExists: Boolean = false,
            ciExists: Boolean = false,
        ) {
            every { encryptionService.hash(request.email) } returns "email-hash"
            every { userRepository.existsByEmailHash("email-hash") } returns emailExists

            if (!emailExists) {
                every { encryptionService.hash(verifiedCi) } returns "ci-hash"
                every { userRepository.existsByCiHash("ci-hash") } returns ciExists

                if (!ciExists) {
                    every { encryptionService.encrypt(request.email) } returns "encrypted-email"
                    every { encryptionService.encrypt(request.name) } returns "encrypted-name"
                    every { encryptionService.encrypt(request.phone) } returns "encrypted-phone"
                    every { encryptionService.encrypt(verifiedCi) } returns "encrypted-ci"
                    every { encryptionService.hash(request.phone) } returns "phone-hash"
                    every { passwordEncoder.encode(request.password) } returns "bcrypt-password-hash"
                }
            }
        }

        @Test
        fun `회원가입 성공 - SignupResponse 반환 (id, email, name 포함)`() {
            // given
            val request = createSignupRequest()
            val userId = UUID.randomUUID()
            val savedUser = User(
                id = userId, email = "encrypted-email", emailHash = "email-hash",
                passwordHash = "bcrypt-password-hash", name = "encrypted-name",
                phone = "encrypted-phone", phoneHash = "phone-hash",
                ci = "encrypted-ci", ciHash = "ci-hash", di = "test-di-value"
            )
            mockSignupDependencies(request)
            every { userRepository.save(any()) } returns savedUser

            // when
            val response = authTransactionalService.processSignup(request, "test-ci-value", "test-di-value")

            // then
            response.id shouldBe userId
            response.email shouldBe request.email
            response.name shouldBe request.name
        }

        @Test
        fun `성공 시 암호화된 데이터 저장 (User 엔티티 slot capture)`() {
            // given
            val request = createSignupRequest()
            val userSlot = slot<User>()
            val savedUser = User(
                id = UUID.randomUUID(), email = "encrypted-email", emailHash = "email-hash",
                passwordHash = "bcrypt-password-hash", name = "encrypted-name",
                phone = "encrypted-phone", phoneHash = "phone-hash",
                ci = "encrypted-ci", ciHash = "ci-hash", di = "test-di-value"
            )
            mockSignupDependencies(request)
            every { userRepository.save(capture(userSlot)) } returns savedUser

            // when
            authTransactionalService.processSignup(request, "test-ci-value", "test-di-value")

            // then
            val capturedUser = userSlot.captured
            capturedUser.email shouldBe "encrypted-email"
            capturedUser.emailHash shouldBe "email-hash"
            capturedUser.name shouldBe "encrypted-name"
            capturedUser.phone shouldBe "encrypted-phone"
            capturedUser.phoneHash shouldBe "phone-hash"
            capturedUser.ci shouldBe "encrypted-ci"
            capturedUser.ciHash shouldBe "ci-hash"
            capturedUser.passwordHash shouldBe "bcrypt-password-hash"
        }

        @Test
        fun `이메일 중복 - ALREADY_EXISTS_EMAIL 예외`() {
            // given
            val request = createSignupRequest()
            mockSignupDependencies(request, emailExists = true)

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processSignup(request, "test-ci-value", "test-di-value")
            }

            exception.errorCode shouldBe ErrorCode.ALREADY_EXISTS_EMAIL
            verify(exactly = 0) { userRepository.save(any()) }
        }

        @Test
        fun `CI 중복 - DUPLICATE_IDENTITY 예외`() {
            // given
            val request = createSignupRequest()
            mockSignupDependencies(request, ciExists = true)

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processSignup(request, "test-ci-value", "test-di-value")
            }

            exception.errorCode shouldBe ErrorCode.DUPLICATE_IDENTITY
            verify(exactly = 0) { userRepository.save(any()) }
        }

        @Test
        fun processSignup_shouldPerformEmailCheckBeforeCICheck_whenEmailExists() {
            // given
            val request = createSignupRequest()
            val callOrder = mutableListOf<String>()

            every { encryptionService.hash(request.email) } returns "email-hash"
            every { userRepository.existsByEmailHash("email-hash") } answers {
                callOrder.add("emailCheck")
                true
            }
            every { encryptionService.hash("test-ci-value") } answers {
                callOrder.add("ciHash")
                "ci-hash"
            }

            // when & then
            assertThrows<UserException> {
                authTransactionalService.processSignup(request, "test-ci-value", "test-di-value")
            }

            callOrder shouldBe listOf("emailCheck")
            verify(exactly = 0) { encryptionService.hash("test-ci-value") }
        }
    }

    // ─── processRefresh 테스트 ────────────────────────────────────────────────────

    @Nested
    inner class ProcessRefreshTest {

        private val tokenFamily: UUID = UUID.randomUUID()

        private fun createStoredToken(
            user: User,
            tokenFamily: UUID = this.tokenFamily,
            rawToken: String = "raw-refresh-token",
            expiresAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC).plusDays(7),
            revoked: Boolean = false,
        ) = RefreshToken(
            user = user,
            tokenFamily = tokenFamily,
            refreshToken = rawToken,
            expiresAt = expiresAt,
            revoked = revoked,
        )

        private fun mockSuccessfulRefresh(user: User, email: String = "test@example.com") {
            every { encryptionService.decrypt(user.email) } returns email
            every { jwtTokenProvider.generateAccessToken(user.id!!, user.role, email) } returns Pair("new-access-token", "new-access-jti")
            every { jwtTokenProvider.generateRefreshToken(user.id!!) } returns Pair("new-refresh-token", "new-refresh-jti")
            every { refreshTokenRepository.save(any()) } returns mockk()
        }

        @Test
        fun processRefresh_shouldReturnLoginResponse_whenValid() {
            // given
            val user = createActiveUser()
            val storedToken = createStoredToken(user)
            every { refreshTokenRepository.findByRefreshToken("raw-refresh-token") } returns storedToken
            mockSuccessfulRefresh(user)

            // when
            val response = authTransactionalService.processRefresh("raw-refresh-token")

            // then
            response.accessToken shouldBe "new-access-token"
            response.refreshToken shouldBe "new-refresh-token"
            response.expiresIn shouldBe 3600L
            response.tokenType shouldBe "Bearer"
        }

        @Test
        fun processRefresh_shouldRevokeExistingToken_whenValid() {
            // given
            val user = createActiveUser()
            val storedToken = createStoredToken(user)
            every { refreshTokenRepository.findByRefreshToken("raw-refresh-token") } returns storedToken
            mockSuccessfulRefresh(user)

            // when
            authTransactionalService.processRefresh("raw-refresh-token")

            // then
            storedToken.revoked shouldBe true
            storedToken.revokedAt shouldNotBe null
        }

        @Test
        fun processRefresh_shouldMaintainTokenFamily_whenValid() {
            // given
            val user = createActiveUser()
            val storedToken = createStoredToken(user, tokenFamily = tokenFamily)
            val savedTokenSlot = slot<RefreshToken>()
            every { refreshTokenRepository.findByRefreshToken("raw-refresh-token") } returns storedToken
            every { encryptionService.decrypt(user.email) } returns "test@example.com"
            every { jwtTokenProvider.generateAccessToken(any(), any(), any()) } returns Pair("new-access-token", "new-access-jti")
            every { jwtTokenProvider.generateRefreshToken(any()) } returns Pair("new-refresh-token", "new-refresh-jti")
            every { refreshTokenRepository.save(capture(savedTokenSlot)) } returns mockk()

            // when
            authTransactionalService.processRefresh("raw-refresh-token")

            // then
            savedTokenSlot.captured.tokenFamily shouldBe tokenFamily
            savedTokenSlot.captured.refreshToken shouldBe "new-refresh-token"
        }

        @Test
        fun processRefresh_shouldThrowInvalidToken_whenTokenNotFound() {
            // given
            every { refreshTokenRepository.findByRefreshToken("unknown-token") } returns null

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processRefresh("unknown-token")
            }

            exception.errorCode shouldBe ErrorCode.INVALID_TOKEN
            verify(exactly = 0) { refreshTokenRepository.save(any()) }
        }

        @Test
        fun processRefresh_shouldThrowExpiredToken_whenTokenExpiredByDb() {
            // given
            val user = createActiveUser()
            val expiredToken = createStoredToken(
                user = user,
                expiresAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
            )
            every { refreshTokenRepository.findByRefreshToken("expired-token") } returns expiredToken

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processRefresh("expired-token")
            }

            exception.errorCode shouldBe ErrorCode.EXPIRED_TOKEN
            verify(exactly = 0) { refreshTokenRepository.save(any()) }
        }

        @Test
        fun processRefresh_shouldThrowRevokedRefreshToken_whenRevokedTokenReused() {
            // given
            val user = createActiveUser()
            val revokedToken = createStoredToken(user, rawToken = "revoked-token", revoked = true)
            every { refreshTokenRepository.findByRefreshToken("revoked-token") } returns revokedToken
            every { refreshTokenRepository.findAllByTokenFamilyAndRevokedFalse(tokenFamily) } returns emptyList()

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processRefresh("revoked-token")
            }

            exception.errorCode shouldBe ErrorCode.REVOKED_REFRESH_TOKEN
            verify(exactly = 0) { refreshTokenRepository.save(any()) }
        }

        @Test
        fun processRefresh_shouldRevokeAllTokensInFamily_whenRevokedTokenDetected() {
            // given
            val user = createActiveUser()
            val revokedToken = createStoredToken(user, rawToken = "revoked-token", revoked = true)
            val activeToken1 = createStoredToken(user, rawToken = "active-token-1")
            val activeToken2 = createStoredToken(user, rawToken = "active-token-2")
            every { refreshTokenRepository.findByRefreshToken("revoked-token") } returns revokedToken
            every { refreshTokenRepository.findAllByTokenFamilyAndRevokedFalse(tokenFamily) } returns listOf(activeToken1, activeToken2)

            // when & then
            assertThrows<UserException> {
                authTransactionalService.processRefresh("revoked-token")
            }

            activeToken1.revoked shouldBe true
            activeToken2.revoked shouldBe true
        }

        @Test
        fun processRefresh_shouldThrowRevokedRefreshToken_whenExpiredAndRevoked() {
            // given
            val user = createActiveUser()
            val expiredRevokedToken = createStoredToken(
                user = user,
                rawToken = "expired-revoked-token",
                revoked = true,
                expiresAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1),
            )
            every { refreshTokenRepository.findByRefreshToken("expired-revoked-token") } returns expiredRevokedToken
            every { refreshTokenRepository.findAllByTokenFamilyAndRevokedFalse(tokenFamily) } returns emptyList()

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processRefresh("expired-revoked-token")
            }

            // EXPIRED_TOKEN이 아닌 REVOKED_REFRESH_TOKEN이어야 함 (탈취 우선 감지)
            exception.errorCode shouldBe ErrorCode.REVOKED_REFRESH_TOKEN
        }

        @Test
        fun processRefresh_shouldThrowInvalidCredentials_whenDeletedAccount() {
            // given
            val user = createActiveUser(status = UserStatus.DELETED)
            val storedToken = createStoredToken(user)
            every { refreshTokenRepository.findByRefreshToken("raw-refresh-token") } returns storedToken

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processRefresh("raw-refresh-token")
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            storedToken.revoked shouldBe true
            verify(exactly = 0) { refreshTokenRepository.save(any()) }
        }

        @Test
        fun processRefresh_shouldThrowInvalidCredentials_whenDormantAccount() {
            // given
            val user = createActiveUser(status = UserStatus.DORMANT)
            val storedToken = createStoredToken(user)
            every { refreshTokenRepository.findByRefreshToken("raw-refresh-token") } returns storedToken

            // when & then
            val exception = assertThrows<UserException> {
                authTransactionalService.processRefresh("raw-refresh-token")
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            storedToken.revoked shouldBe true
            verify(exactly = 0) { refreshTokenRepository.save(any()) }
        }
    }
}
