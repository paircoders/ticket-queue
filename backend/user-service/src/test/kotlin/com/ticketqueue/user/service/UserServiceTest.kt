package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.dto.UserDto
import com.ticketqueue.user.entity.User
import com.ticketqueue.user.entity.UserRole
import com.ticketqueue.user.entity.UserStatus
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.repository.UserRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var encryptionService: EncryptionService
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var userService: UserService

    private val userId = UUID.randomUUID()
    private val createdAt = LocalDateTime.now().minusDays(10)

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        encryptionService = mockk()
        passwordEncoder = mockk()
        userService = UserService(userRepository, encryptionService, passwordEncoder)
    }

    private fun createUser(
        id: UUID = userId,
        email: String = "enc:test@example.com",
        name: String = "enc:홍길동",
        phone: String = "enc:01012345678",
        passwordHash: String = "password-hash",
        status: UserStatus = UserStatus.ACTIVE,
        role: UserRole = UserRole.USER,
    ) = User(
        id = id,
        email = email,
        emailHash = "email-hash",
        passwordHash = passwordHash,
        name = name,
        phone = phone,
        phoneHash = "phone-hash",
        role = role,
        status = status,
        createdAt = createdAt,
    )

    @Nested
    @DisplayName("getProfile")
    inner class GetProfile {

        @Test
        @DisplayName("정상 조회 - 복호화된 필드 반환")
        fun shouldReturnDecryptedProfile() {
            val user = createUser()
            every { userRepository.findById(userId) } returns Optional.of(user)
            every { encryptionService.decrypt("enc:test@example.com") } returns "test@example.com"
            every { encryptionService.decrypt("enc:홍길동") } returns "홍길동"
            every { encryptionService.decrypt("enc:01012345678") } returns "010-1234-5678"

            val response = userService.getProfile(userId)

            response.id shouldBe userId
            response.email shouldBe "test@example.com"
            response.name shouldBe "홍길동"
            response.phone shouldBe "010-1234-5678"
            response.role shouldBe UserRole.USER
            response.createdAt shouldBe createdAt
        }

        @Test
        @DisplayName("사용자 없음 - RESOURCE_NOT_FOUND")
        fun shouldThrowWhenUserNotFound() {
            every { userRepository.findById(userId) } returns Optional.empty()

            val exception = assertThrows<UserException> {
                userService.getProfile(userId)
            }
            exception.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
        }

        @Test
        @DisplayName("탈퇴된 사용자 - RESOURCE_NOT_FOUND")
        fun shouldThrowWhenUserDeleted() {
            val user = createUser(status = UserStatus.DELETED)
            every { userRepository.findById(userId) } returns Optional.of(user)

            val exception = assertThrows<UserException> {
                userService.getProfile(userId)
            }
            exception.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            verify(exactly = 0) { encryptionService.decrypt(any()) }
        }
    }

    @Nested
    @DisplayName("updateProfile")
    inner class UpdateProfile {

        private val request = UserDto.UpdateProfileRequest(
            name = "김철수",
            phone = "010-9876-5432",
        )

        @Test
        @DisplayName("정상 수정 - 암호화된 name/phone 및 phoneHash 갱신")
        fun shouldUpdateProfile() {
            val user = createUser()
            every { userRepository.findById(userId) } returns Optional.of(user)
            every { encryptionService.encrypt("김철수") } returns "enc:김철수"
            every { encryptionService.encrypt("010-9876-5432") } returns "enc:010-9876-5432"
            every { encryptionService.hash("010-9876-5432") } returns "new-phone-hash"

            val response = userService.updateProfile(userId, request)

            response.id shouldBe userId
            response.name shouldBe "김철수"
            response.phone shouldBe "010-9876-5432"
            user.name shouldBe "enc:김철수"
            user.phone shouldBe "enc:010-9876-5432"
            user.phoneHash shouldBe "new-phone-hash"
        }

        @Test
        @DisplayName("사용자 없음 - RESOURCE_NOT_FOUND")
        fun shouldThrowWhenUserNotFound() {
            every { userRepository.findById(userId) } returns Optional.empty()

            val exception = assertThrows<UserException> {
                userService.updateProfile(userId, request)
            }
            exception.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            verify(exactly = 0) { encryptionService.encrypt(any()) }
        }

        @Test
        @DisplayName("탈퇴된 사용자 - RESOURCE_NOT_FOUND")
        fun shouldThrowWhenUserDeleted() {
            val user = createUser(status = UserStatus.DELETED)
            every { userRepository.findById(userId) } returns Optional.of(user)

            val exception = assertThrows<UserException> {
                userService.updateProfile(userId, request)
            }
            exception.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            verify(exactly = 0) { encryptionService.encrypt(any()) }
        }
    }

    @Nested
    @DisplayName("changePassword")
    inner class ChangePassword {

        private val request = UserDto.ChangePasswordRequest(
            currentPassword = "oldPassword!",
            newPassword = "newSecurePassword123!",
        )

        @Test
        @DisplayName("정상 변경 - 새 비밀번호 해시 저장")
        fun shouldChangePassword() {
            val user = createUser(passwordHash = "old-hash")
            every { userRepository.findById(userId) } returns Optional.of(user)
            every { passwordEncoder.matches("oldPassword!", "old-hash") } returns true
            every { passwordEncoder.encode("newSecurePassword123!") } returns "new-hash"

            userService.changePassword(userId, request)

            user.passwordHash shouldBe "new-hash"
            verify(exactly = 1) { passwordEncoder.matches("oldPassword!", "old-hash") }
            verify(exactly = 1) { passwordEncoder.encode("newSecurePassword123!") }
        }

        @Test
        @DisplayName("사용자 없음 - RESOURCE_NOT_FOUND")
        fun shouldThrowWhenUserNotFound() {
            every { userRepository.findById(userId) } returns Optional.empty()

            val exception = assertThrows<UserException> {
                userService.changePassword(userId, request)
            }
            exception.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
            verify(exactly = 0) { passwordEncoder.encode(any()) }
        }

        @Test
        @DisplayName("탈퇴된 사용자 - RESOURCE_NOT_FOUND")
        fun shouldThrowWhenUserDeleted() {
            val user = createUser(status = UserStatus.DELETED)
            every { userRepository.findById(userId) } returns Optional.of(user)

            val exception = assertThrows<UserException> {
                userService.changePassword(userId, request)
            }
            exception.errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            verify(exactly = 0) { passwordEncoder.matches(any(), any()) }
        }

        @Test
        @DisplayName("현재 비밀번호 불일치 - INVALID_CREDENTIALS, 새 비밀번호 인코딩 미수행")
        fun shouldThrowWhenCurrentPasswordMismatch() {
            val user = createUser(passwordHash = "old-hash")
            every { userRepository.findById(userId) } returns Optional.of(user)
            every { passwordEncoder.matches("oldPassword!", "old-hash") } returns false

            val exception = assertThrows<UserException> {
                userService.changePassword(userId, request)
            }
            exception.errorCode shouldBe ErrorCode.INVALID_CREDENTIALS
            user.passwordHash shouldBe "old-hash"
            verify(exactly = 0) { passwordEncoder.encode(any()) }
        }
    }
}
