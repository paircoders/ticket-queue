package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.entity.User
import com.ticketqueue.user.entity.UserRole
import com.ticketqueue.user.entity.UserStatus
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

class AuthServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var recaptchaService: RecaptchaService
    private lateinit var encryptionService: EncryptionService
    private lateinit var authService: AuthService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        passwordEncoder = mockk()
        recaptchaService = mockk()
        encryptionService = mockk()
        authService = AuthService(userRepository, passwordEncoder, recaptchaService, encryptionService)
    }

    private fun createSignupRequest(
        email: String = "test@example.com",
        password: String = "password123!",
        name: String = "홍길동",
        phone: String = "01012345678",
        ci: String = "test-ci-value",
        di: String = "test-di-value",
        recaptchaToken: String = "valid-recaptcha-token"
    ) = AuthDto.SignupRequest(
        email = email,
        password = password,
        name = name,
        phone = phone,
        ci = ci,
        di = di,
        recaptchaToken = recaptchaToken
    )

    @Test
    fun `회원가입 성공 - SignupResponse 반환 (id, email, name 포함)`() {
        // given
        val request = createSignupRequest()
        val userId = UUID.randomUUID()

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
            di = request.di,
            role = UserRole.USER,
            status = UserStatus.ACTIVE
        )

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns false
        every { encryptionService.hash(request.ci) } returns "ci-hash"
        every { userRepository.existsByCiHash("ci-hash") } returns false
        every { encryptionService.encrypt(request.email) } returns "encrypted-email"
        every { encryptionService.encrypt(request.name) } returns "encrypted-name"
        every { encryptionService.encrypt(request.phone) } returns "encrypted-phone"
        every { encryptionService.encrypt(request.ci) } returns "encrypted-ci"
        every { encryptionService.hash(request.phone) } returns "phone-hash"
        every { passwordEncoder.encode(request.password) } returns "bcrypt-password-hash"
        every { userRepository.save(any()) } returns savedUser

        // when
        val response = authService.signup(request)

        // then
        assertThat(response).isNotNull
        assertThat(response.id).isEqualTo(userId)
        assertThat(response.email).isEqualTo(request.email)
        assertThat(response.name).isEqualTo(request.name)
    }

    @Test
    fun `성공 시 암호화된 데이터 저장 (User 엔티티 slot capture)`() {
        // given
        val request = createSignupRequest()
        val userId = UUID.randomUUID()
        val userSlot = slot<User>()

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
            di = request.di
        )

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns false
        every { encryptionService.hash(request.ci) } returns "ci-hash"
        every { userRepository.existsByCiHash("ci-hash") } returns false
        every { encryptionService.encrypt(request.email) } returns "encrypted-email"
        every { encryptionService.encrypt(request.name) } returns "encrypted-name"
        every { encryptionService.encrypt(request.phone) } returns "encrypted-phone"
        every { encryptionService.encrypt(request.ci) } returns "encrypted-ci"
        every { encryptionService.hash(request.phone) } returns "phone-hash"
        every { passwordEncoder.encode(request.password) } returns "bcrypt-password-hash"
        every { userRepository.save(capture(userSlot)) } returns savedUser

        // when
        authService.signup(request)

        // then
        val capturedUser = userSlot.captured
        assertThat(capturedUser.email).isEqualTo("encrypted-email")
        assertThat(capturedUser.emailHash).isEqualTo("email-hash")
        assertThat(capturedUser.name).isEqualTo("encrypted-name")
        assertThat(capturedUser.phone).isEqualTo("encrypted-phone")
        assertThat(capturedUser.phoneHash).isEqualTo("phone-hash")
        assertThat(capturedUser.ci).isEqualTo("encrypted-ci")
        assertThat(capturedUser.ciHash).isEqualTo("ci-hash")
        assertThat(capturedUser.di).isEqualTo(request.di)
    }

    @Test
    fun `성공 시 BCrypt 비밀번호 인코딩`() {
        // given
        val request = createSignupRequest()
        val userId = UUID.randomUUID()
        val userSlot = slot<User>()

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
            di = request.di
        )

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns false
        every { encryptionService.hash(request.ci) } returns "ci-hash"
        every { userRepository.existsByCiHash("ci-hash") } returns false
        every { encryptionService.encrypt(request.email) } returns "encrypted-email"
        every { encryptionService.encrypt(request.name) } returns "encrypted-name"
        every { encryptionService.encrypt(request.phone) } returns "encrypted-phone"
        every { encryptionService.encrypt(request.ci) } returns "encrypted-ci"
        every { encryptionService.hash(request.phone) } returns "phone-hash"
        every { passwordEncoder.encode(request.password) } returns "bcrypt-password-hash"
        every { userRepository.save(capture(userSlot)) } returns savedUser

        // when
        authService.signup(request)

        // then
        val capturedUser = userSlot.captured
        assertThat(capturedUser.passwordHash).isEqualTo("bcrypt-password-hash")
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

        assertThat(exception.errorCode).isEqualTo(ErrorCode.RECAPTCHA_FAILED)
    }

    @Test
    fun `이메일 중복 - ALREADY_EXISTS_EMAIL 예외`() {
        // given
        val request = createSignupRequest()

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns true

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.ALREADY_EXISTS_EMAIL)
    }

    @Test
    fun `CI 중복 - ALREADY_EXISTS_USER 예외`() {
        // given
        val request = createSignupRequest()

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns false
        every { encryptionService.hash(request.ci) } returns "ci-hash"
        every { userRepository.existsByCiHash("ci-hash") } returns true

        // when & then
        val exception = assertThrows<UserException> {
            authService.signup(request)
        }

        assertThat(exception.errorCode).isEqualTo(ErrorCode.ALREADY_EXISTS_USER)
    }

    @Test
    fun `reCAPTCHA가 이메일 체크보다 먼저 수행`() {
        // given
        val request = createSignupRequest()
        val callOrder = mutableListOf<String>()

        every { recaptchaService.verify(request.recaptchaToken) } answers {
            callOrder.add("recaptcha")
            false
        }
        every { encryptionService.hash(request.email) } answers {
            callOrder.add("emailHash")
            "email-hash"
        }

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        assertThat(callOrder).containsExactly("recaptcha")
        verify(exactly = 0) { encryptionService.hash(request.email) }
    }

    @Test
    fun `이메일 체크가 CI 체크보다 먼저 수행`() {
        // given
        val request = createSignupRequest()
        val callOrder = mutableListOf<String>()

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } answers {
            callOrder.add("emailHash")
            "email-hash"
        }
        every { userRepository.existsByEmailHash("email-hash") } answers {
            callOrder.add("emailCheck")
            true
        }
        every { encryptionService.hash(request.ci) } answers {
            callOrder.add("ciHash")
            "ci-hash"
        }

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        assertThat(callOrder).containsExactly("emailHash", "emailCheck")
        verify(exactly = 0) { encryptionService.hash(request.ci) }
    }

    @Test
    fun `이메일 해시로 중복 체크 수행`() {
        // given
        val request = createSignupRequest()

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns true

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
            di = request.di
        )

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns false
        every { encryptionService.hash(request.ci) } returns "ci-hash"
        every { userRepository.existsByCiHash("ci-hash") } returns false
        every { encryptionService.encrypt(request.email) } returns "encrypted-email"
        every { encryptionService.encrypt(request.name) } returns "encrypted-name"
        every { encryptionService.encrypt(request.phone) } returns "encrypted-phone"
        every { encryptionService.encrypt(request.ci) } returns "encrypted-ci"
        every { encryptionService.hash(request.phone) } returns "phone-hash"
        every { passwordEncoder.encode(request.password) } returns "bcrypt-password-hash"
        every { userRepository.save(capture(userSlot)) } returns savedUser

        // when
        authService.signup(request)

        // then
        val capturedUser = userSlot.captured
        assertThat(capturedUser.phoneHash).isEqualTo("phone-hash")
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

        every { recaptchaService.verify(request.recaptchaToken) } returns true
        every { encryptionService.hash(request.email) } returns "email-hash"
        every { userRepository.existsByEmailHash("email-hash") } returns true

        // when & then
        assertThrows<UserException> {
            authService.signup(request)
        }

        verify(exactly = 0) { userRepository.save(any()) }
    }
}
