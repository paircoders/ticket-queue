package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.config.JwtProperties
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.entity.RefreshToken
import com.ticketqueue.user.entity.User
import com.ticketqueue.user.entity.UserStatus
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.repository.RefreshTokenRepository
import com.ticketqueue.user.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val recaptchaService: RecaptchaService,
    private val encryptionService: EncryptionService,
    private val portoneService: PortoneService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val loginHistoryRecorder: LoginHistoryRecorder,
    private val jwtProperties: JwtProperties,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    fun signup(request: AuthDto.SignupRequest): AuthDto.SignupResponse {

        // 1. reCAPTCHA 검증
        verifyRecaptcha(request.recaptchaToken)

        // 2. PortOne 본인인증 검증
        val verifiedCustomer = portoneService.verifyIdentity(request.identityVerificationId)
        val verifiedCi = verifiedCustomer.ci ?: throw UserException(ErrorCode.PORTONE_MISSING_REQUIRED_INFO)
        val verifiedDi = verifiedCustomer.di ?: throw UserException(ErrorCode.PORTONE_MISSING_REQUIRED_INFO)

        // 3. 중복 체크 (이메일 및 CI)
        val emailHash = encryptionService.hash(request.email)
        validateDuplicateEmail(emailHash)

        val ciHash = encryptionService.hash(verifiedCi)
        validateDuplicateIdentity(ciHash)

        // 4. 엔티티 생성 및 저장
        val user = createUserEntity(request, verifiedCi, verifiedDi, emailHash, ciHash)
        val savedUser = userRepository.save(user)

        logger.info { "User signed up successfully: id=${savedUser.id}" }

        return AuthDto.SignupResponse.from(savedUser, request.email, request.name)
    }

    @Transactional
    fun login(
        request: AuthDto.LoginRequest,
        ipAddress: String = "",
        userAgent: String = "",
    ): AuthDto.LoginResponse {

        // 1. reCAPTCHA 검증
        verifyRecaptcha(request.recaptchaToken)

        // 2. 사용자 조회 및 상태 체크
        val emailHash = encryptionService.hash(request.email)
        val user = userRepository.findByEmailHash(emailHash)
            ?: throw UserException(ErrorCode.INVALID_CREDENTIALS)

        if (user.status == UserStatus.DELETED) {
            logger.error { "Login attempt for deleted user: id=${user.id}" }
            loginHistoryRecorder.recordFailure(user, ipAddress, userAgent, "ACCOUNT_DELETED")
            throw UserException(ErrorCode.INVALID_CREDENTIALS)
        }

        if (user.status == UserStatus.DORMANT) {
            logger.error { "Login attempt for dormant user: id=${user.id}" }
            loginHistoryRecorder.recordFailure(user, ipAddress, userAgent, "ACCOUNT_DORMANT")
            throw UserException(ErrorCode.INVALID_CREDENTIALS)
        }

        // 3. 비밀번호 검증
        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            logger.error { "Login failed: Invalid password for user id=${user.id}" }
            loginHistoryRecorder.recordFailure(user, ipAddress, userAgent, "INVALID_PASSWORD")
            throw UserException(ErrorCode.INVALID_CREDENTIALS)
        }

        // 4. 토큰 발급 (Access & Refresh with RTR)
        val (accessToken, refreshToken) = try {
            issueTokens(user, request.email)
        } catch (e: IllegalStateException) {
            logger.error(e) { "JWT secret 설정 오류로 토큰 발급 실패: userId=${user.id}, 원인: ${e.message}" }
            loginHistoryRecorder.recordFailure(user, ipAddress, userAgent, "JWT_CONFIG_ERROR")
            throw UserException(ErrorCode.JWT_CONFIGURATION_ERROR, cause = e)
        }

        // 5. 로그인 이력 및 접속 시점 갱신
        loginHistoryRecorder.recordSuccess(user, ipAddress, userAgent)

        logger.info { "User logged in successfully: id=${user.id}" }

        return AuthDto.LoginResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = Duration.ofMillis(jwtProperties.accessTokenExpiry).seconds,
        )
    }

    private fun verifyRecaptcha(token: String) {
        if (!recaptchaService.verify(token)) {
            logger.error { "reCAPTCHA verification failed" }
            throw UserException(ErrorCode.RECAPTCHA_FAILED)
        }
    }

    private fun validateDuplicateEmail(emailHash: String) {
        if (userRepository.existsByEmailHash(emailHash)) {
            logger.error { "Signup failed: Email already exists" }
            throw UserException(ErrorCode.ALREADY_EXISTS_EMAIL)
        }
    }

    private fun validateDuplicateIdentity(ciHash: String) {
        if (userRepository.existsByCiHash(ciHash)) {
            logger.error { "Signup failed: Identity (CI) already exists" }
            throw UserException(ErrorCode.DUPLICATE_IDENTITY)
        }
    }

    private fun createUserEntity(
        request: AuthDto.SignupRequest,
        verifiedCi: String,
        verifiedDi: String,
        emailHash: String,
        ciHash: String
    ): User {
        val encryptedEmail = encryptionService.encrypt(request.email)
        val encryptedName = encryptionService.encrypt(request.name)
        val encryptedPhone = encryptionService.encrypt(request.phone)
        val encryptedCi = encryptionService.encrypt(verifiedCi)

        val phoneHash = encryptionService.hash(request.phone)
        val passwordHash = passwordEncoder.encode(request.password)

        return User(
            email = encryptedEmail,
            emailHash = emailHash,
            passwordHash = passwordHash,
            name = encryptedName,
            phone = encryptedPhone,
            phoneHash = phoneHash,
            ci = encryptedCi,
            ciHash = ciHash,
            di = verifiedDi
        )
    }

    private fun issueTokens(user: User, email: String): Pair<String, String> {
        val (accessToken, accessTokenJti) = jwtTokenProvider.generateAccessToken(user.id!!, user.role, email)
        val (refreshToken, _) = jwtTokenProvider.generateRefreshToken(user.id)

        val refreshTokenEntity = RefreshToken(
            user = user,
            tokenFamily = UUID.randomUUID(), // RTR 초기화를 위해 새로운 family 생성
            refreshToken = refreshToken,
            accessTokenJti = accessTokenJti,
            expiresAt = LocalDateTime.now().plusSeconds(Duration.ofMillis(jwtProperties.refreshTokenExpiry).seconds),
        )
        refreshTokenRepository.save(refreshTokenEntity)

        return Pair(accessToken, refreshToken)
    }


}
