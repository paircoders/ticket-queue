package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.external.portone.PortoneIdentityV2Response
import com.ticketqueue.user.config.JwtProperties
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.entity.User
import com.ticketqueue.user.entity.UserRole
import com.ticketqueue.user.entity.UserStatus
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.repository.LoginHistoryRepository
import com.ticketqueue.user.repository.RefreshTokenRepository
import com.ticketqueue.user.repository.UserRepository
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Base64
import java.util.UUID

class AuthServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var recaptchaService: RecaptchaService
    private lateinit var encryptionService: EncryptionService
    private lateinit var portoneService: PortoneService
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var refreshTokenRepository: RefreshTokenRepository
    private lateinit var loginHistoryRepository: LoginHistoryRepository
    private lateinit var authService: AuthService

    private val jwtProperties = JwtProperties(
        secret = Base64.getEncoder().encodeToString("test-secret-key-at-least-32-bytes-long!!".toByteArray()),
        accessTokenExpiry = 3600000L,
        refreshTokenExpiry = 604800000L,
    )

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        passwordEncoder = mockk()
        recaptchaService = mockk()
        encryptionService = mockk()
        portoneService = mockk()
        jwtTokenProvider = mockk()
        refreshTokenRepository = mockk()
        loginHistoryRepository = mockk()
        authService = AuthService(
            userRepository, passwordEncoder, recaptchaService, encryptionService, portoneService,
            jwtTokenProvider, refreshTokenRepository, loginHistoryRepository, jwtProperties
        )
    }

    // ─── Signup 테스트 ────────────────────────────────────────────────────────────

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

    @Test
    fun `회원가입 성공 - SignupResponse 반환 (id, email, name 포함)`() {
        // given
        val request = createSignupRequest()
        val userId = UUID.randomUUID()
        val verifiedCustomer = createVerifiedCustomer()

        val savedUser = User(
            id = userId,
            email = "encrypted-email",
            emailHash = "email-hash",
            passwordHash = "bcrypt-password-hash",
            name = "encrypted-name",
            phone = "encrypted-phone",
            phoneHash = "phone-hash",
            ci = "encrypted-ci",
            ciHash = "ci-hash",
            di = verifiedCustomer.di!!
        )

        mockSignupDependencies(request, verifiedCustomer)
        every { userRepository.save(any()) } returns savedUser

        // when
        val response = authService.signup(request)

        // then
        response.shouldNotBeNull()
        response.id shouldBe userId
        response.email shouldBe request.email
        response.name shouldBe request.name
    }

    @Test
    fun `성공 시 암호화된 데이터 저장 (User 엔티티 slot capture)`() {
        // given
        val request = createSignupRequest()
        val userId = UUID.randomUUID()
        val userSlot = slot<User>()
        val verifiedCustomer = createVerifiedCustomer()

        val savedUser = User(
            id = userId,
            email = "encrypted-email",
            emailHash = "email-hash",
            passwordHash = "bcrypt-password-hash",
            name = "encrypted-name",
            phone = "encrypted-phone",
            phoneHash = "phone-hash",
            ci = "encrypted-ci",
            ciHash = "ci-hash",
            di = verifiedCustomer.di!!
        )

        mockSignupDependencies(request, verifiedCustomer)
        every { userRepository.save(capture(userSlot)) } returns savedUser

        // when
        authService.signup(request)

        // then
        val capturedUser = userSlot.captured
        capturedUser.email shouldBe "encrypted-email"
        capturedUser.emailHash shouldBe "email-hash"
        capturedUser.name shouldBe "encrypted-name"
        capturedUser.phone shouldBe "encrypted-phone"
        capturedUser.phoneHash shouldBe "phone-hash"
        capturedUser.ci shouldBe "encrypted-ci"
        capturedUser.ciHash shouldBe "ci-hash"
        capturedUser.di shouldBe verifiedCustomer.di
    }

    private fun mockSignupDependencies(
        request: AuthDto.SignupRequest,
        verifiedCustomer: PortoneIdentityV2Response.VerifiedCustomerDetail,
        emailExists: Boolean = false,
        ciExists: Boolean = false
    ) {
        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { portoneService.verifyIdentity(request.identityVerificationId) } returns verifiedCustomer
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns emailExists

        if (!emailExists) {
            every { encryptionService.hash(verifiedCustomer.ci!!) } returns "ci-hash"
            every { userRepository.existsByCiHash("ci-hash") } returns ciExists

            if (!ciExists) {
                every { encryptionService.encrypt(request.email) } returns "encrypted-email"
                every { encryptionService.encrypt(request.name) } returns "encrypted-name"
                every { encryptionService.encrypt(request.phone) } returns "encrypted-phone"
                every { encryptionService.encrypt(verifiedCustomer.ci!!) } returns "encrypted-ci"
                every { encryptionService.hash(request.phone) } returns "phone-hash"
                every { passwordEncoder.encode(request.password) } returns "bcrypt-password-hash"
            }
        }
    }

    @Test
    fun `성공 시 BCrypt 비밀번호 인코딩`() {
        // given
        val request = createSignupRequest()
        val userId = UUID.randomUUID()
        val userSlot = slot<User>()
        val verifiedCustomer = createVerifiedCustomer()

        val savedUser = User(
            id = userId,
            email = "encrypted-email",
            emailHash = "email-hash",
            passwordHash = "bcrypt-password-hash",
            name = "encrypted-name",
            phone = "encrypted-phone",
            phoneHash = "phone-hash",
            ci = "encrypted-ci",
            ciHash = "ci-hash",
            di = verifiedCustomer.di!!
        )

        mockSignupDependencies(request, verifiedCustomer)
        every { userRepository.save(capture(userSlot)) } returns savedUser

        // when
        authService.signup(request)

        // then
        val capturedUser = userSlot.captured
        capturedUser.passwordHash shouldBe "bcrypt-password-hash"
        verify { passwordEncoder.encode(request.password) }
    }

    @Test
    fun `reCAPTCHA 실패 - RECAPTCHA_FAILED 예외`() {
        // given
        val request = createSignupRequest()
        every { recaptchaService.verify(request.recaptchaToken) } returns false

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        exception.errorCode shouldBe ErrorCode.RECAPTCHA_FAILED
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
    }

    @Test
    fun `이메일 중복 - ALREADY_EXISTS_EMAIL 예외`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer()

        mockSignupDependencies(request, verifiedCustomer, emailExists = true)

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        exception.errorCode shouldBe ErrorCode.ALREADY_EXISTS_EMAIL
    }

    @Test
    fun `CI 중복 - DUPLICATE_IDENTITY 예외`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer()

        mockSignupDependencies(request, verifiedCustomer, ciExists = true)

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        exception.errorCode shouldBe ErrorCode.DUPLICATE_IDENTITY
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
        assertThrows<UserException> {
            authService.signup(request)
        }

        callOrder shouldBe listOf("recaptcha")
        verify(exactly = 0) { portoneService.verifyIdentity(any()) }
    }

    @Test
    fun `PortOne 호출이 이메일 체크보다 먼저 수행`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer()
        val callOrder = mutableListOf<String>()

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { portoneService.verifyIdentity(request.identityVerificationId) } answers {
            callOrder.add("portone")
            throw UserException(ErrorCode.PORTONE_VERIFICATION_FAILED)
        }
        every { encryptionService.hash(request.email) } answers {
            callOrder.add("emailHash")
            "email-hash"
        }

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        callOrder shouldBe listOf("portone")
        verify(exactly = 0) { encryptionService.hash(request.email) }
    }

    @Test
    fun `이메일 체크가 CI 체크보다 먼저 수행`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer()
        val callOrder = mutableListOf<String>()

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { portoneService.verifyIdentity(request.identityVerificationId) } returns verifiedCustomer
        every { encryptionService.hash(request.email) } answers {
            callOrder.add("emailHash")
            "email-hash"
        }
        every { userRepository.existsByEmailHash("email-hash") } answers {
            callOrder.add("emailCheck")
            true
        }
        every { encryptionService.hash(verifiedCustomer.ci!!) } answers {
            callOrder.add("ciHash")
            "ci-hash"
        }

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        callOrder shouldBe listOf("emailHash", "emailCheck")
        verify(exactly = 0) { encryptionService.hash(verifiedCustomer.ci!!) }
    }

    @Test
    fun `이메일 해시로 중복 체크 수행`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer()

        mockSignupDependencies(request, verifiedCustomer, emailExists = true)

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        verify { encryptionService.hash(request.email) }
        verify { userRepository.existsByEmailHash("email-hash") }
    }

    @Test
    fun `phone 해시값도 저장`() {
        // given
        val request = createSignupRequest()
        val userId = UUID.randomUUID()
        val userSlot = slot<User>()
        val verifiedCustomer = createVerifiedCustomer()

        val savedUser = User(
            id = userId,
            email = "encrypted-email",
            emailHash = "email-hash",
            passwordHash = "bcrypt-password-hash",
            name = "encrypted-name",
            phone = "encrypted-phone",
            phoneHash = "phone-hash",
            ci = "encrypted-ci",
            ciHash = "ci-hash",
            di = verifiedCustomer.di!!
        )

        mockSignupDependencies(request, verifiedCustomer)
        every { userRepository.save(capture(userSlot)) } returns savedUser

        // when
        authService.signup(request)

        // then
        val capturedUser = userSlot.captured
        capturedUser.phoneHash shouldBe "phone-hash"
        verify { encryptionService.hash(request.phone) }
    }

    @Test
    fun `reCAPTCHA 실패 시 save 미호출`() {
        // given
        val request = createSignupRequest()
        every { recaptchaService.verify(request.recaptchaToken) } returns false

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `이메일 중복 시 save 미호출`() {
        // given
        val request = createSignupRequest()
        val verifiedCustomer = createVerifiedCustomer()

        mockSignupDependencies(request, verifiedCustomer, emailExists = true)

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        verify(exactly = 0) { userRepository.save(any()) }
    }

    // ─── Login 테스트 ─────────────────────────────────────────────────────────────

    private fun createLoginRequest(
        email: String = "test@example.com",
        password: String = "password123!",
        recaptchaToken: String = "valid-recaptcha-token"
    ) = AuthDto.LoginRequest(
        email = email,
        password = password,
        recaptchaToken = recaptchaToken
    )

    private fun createActiveUser(
        id: UUID = UUID.randomUUID(),
        status: UserStatus = UserStatus.ACTIVE,
        role: UserRole = UserRole.USER
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

    private fun mockLoginDependencies(
        request: AuthDto.LoginRequest,
        user: User,
        passwordMatches: Boolean = true
    ) {
        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.findByEmailHash("email-hash") } returns user
        every { passwordEncoder.matches(request.password, user.passwordHash) } returns passwordMatches
        if (passwordMatches) {
            every { jwtTokenProvider.generateAccessToken(user.id!!, user.role) } returns Pair("access-token", "access-jti")
            every { jwtTokenProvider.generateRefreshToken(user.id!!) } returns Pair("refresh-token", "refresh-jti")
            every { refreshTokenRepository.save(any()) } returns mockk()
            every { loginHistoryRepository.save(any()) } returns mockk()
        }
    }

    @Nested
    inner class LoginTest {

        @Test
        fun `정상 로그인 - LoginResponse 반환`() {
            // given
            val userId = UUID.randomUUID()
            val user = createActiveUser(id = userId)
            val request = createLoginRequest()
            mockLoginDependencies(request, user)

            // when
            val response = authService.login(request)

            // then
            response.accessToken shouldBe "access-token"
            response.refreshToken shouldBe "refresh-token"
            response.expiresIn shouldBe 3600L
            response.tokenType shouldBe "Bearer"
        }

        @Test
        fun `정상 로그인 - RefreshToken DB 저장`() {
            // given
            val userId = UUID.randomUUID()
            val user = createActiveUser(id = userId)
            val request = createLoginRequest()
            mockLoginDependencies(request, user)

            // when
            authService.login(request)

            // then
            verify(exactly = 1) { refreshTokenRepository.save(any()) }
        }

        @Test
        fun `reCAPTCHA 실패 - RECAPTCHA_FAILED 예외`() {
            // given
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } returns false

            // when & then
            val exception = assertThrows<UserException> {
                authService.login(request)
            }

            exception.errorCode shouldBe ErrorCode.RECAPTCHA_FAILED
            verify(exactly = 0) { userRepository.findByEmailHash(any()) }
        }

        @Test
        fun `존재하지 않는 이메일 - INVALID_CREDENTIALS 예외`() {
            // given
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } returns true
            every { encryptionService.hash(request.email) } returns "email-hash"
            every { userRepository.findByEmailHash("email-hash") } returns null

            // when & then
            val exception = assertThrows<UserException> {
                authService.login(request)
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
        }

        @Test
        fun `비밀번호 불일치 - INVALID_CREDENTIALS 예외`() {
            // given
            val user = createActiveUser()
            val request = createLoginRequest()
            mockLoginDependencies(request, user, passwordMatches = false)

            // when & then
            val exception = assertThrows<UserException> {
                authService.login(request)
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
        }

        @Test
        fun `DELETED 계정 로그인 - INVALID_CREDENTIALS 예외`() {
            // given
            val user = createActiveUser(status = UserStatus.DELETED)
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } returns true
            every { encryptionService.hash(request.email) } returns "email-hash"
            every { userRepository.findByEmailHash("email-hash") } returns user

            // when & then
            val exception = assertThrows<UserException> {
                authService.login(request)
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
        }

        @Test
        fun `DORMANT 계정 로그인 - INVALID_CREDENTIALS 예외`() {
            // given
            val user = createActiveUser(status = UserStatus.DORMANT)
            val request = createLoginRequest()
            every { recaptchaService.verify(request.recaptchaToken) } returns true
            every { encryptionService.hash(request.email) } returns "email-hash"
            every { userRepository.findByEmailHash("email-hash") } returns user

            // when & then
            val exception = assertThrows<UserException> {
                authService.login(request)
            }

            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
        }
    }
}
