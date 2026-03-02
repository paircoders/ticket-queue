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
import java.time.ZoneOffset
import java.util.UUID

@Service
@Transactional
class AuthTransactionalService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val encryptionService: EncryptionService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val loginHistoryRecorder: LoginHistoryRecorder,
    private val jwtProperties: JwtProperties,
) {
    private val logger = KotlinLogging.logger {}

    fun processLogin(
        emailHash: String,
        password: String,
        ipAddress: String,
        userAgent: String,
    ): AuthDto.LoginResponse {
        // 1. 사용자 조회
        val user = userRepository.findByEmailHash(emailHash)
        if (user == null) {
            logger.error { "Login attempt for non-existent email" }
            loginHistoryRecorder.recordFailureWithoutUser(ipAddress, userAgent, "USER_NOT_FOUND")
            throw UserException(ErrorCode.INVALID_CREDENTIALS)
        }

        // 2. 계정 상태 체크
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
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            logger.error { "Login failed: Invalid password for user id=${user.id}" }
            loginHistoryRecorder.recordFailure(user, ipAddress, userAgent, "INVALID_PASSWORD")
            throw UserException(ErrorCode.INVALID_CREDENTIALS)
        }

        // 4. 토큰 발급 (Access & Refresh with RTR)
        val email = encryptionService.decrypt(user.email)
        val (accessToken, refreshToken) = try {
            issueTokens(user, email)
        } catch (e: IllegalStateException) {
            logger.error(e) { "JWT secret 설정 오류로 토큰 발급 실패: userId=${user.id}, 원인: ${e.message}" }
            loginHistoryRecorder.recordFailure(user, ipAddress, userAgent, "JWT_CONFIG_ERROR")
            throw UserException(ErrorCode.JWT_CONFIGURATION_ERROR, cause = e)
        } catch (e: Exception) {
            logger.error(e) { "토큰 발급 중 예기치 못한 오류: userId=${user.id}" }
            loginHistoryRecorder.recordFailure(user, ipAddress, userAgent, "TOKEN_ISSUE_ERROR")
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

    fun processSignup(
        request: AuthDto.SignupRequest,
        verifiedCi: String,
        verifiedDi: String,
    ): AuthDto.SignupResponse {
        // 1. 중복 체크 (이메일 및 CI)
        val emailHash = encryptionService.hash(request.email)
        if (userRepository.existsByEmailHash(emailHash)) {
            logger.error { "Signup failed: Email already exists" }
            throw UserException(ErrorCode.ALREADY_EXISTS_EMAIL)
        }

        val ciHash = encryptionService.hash(verifiedCi)
        if (userRepository.existsByCiHash(ciHash)) {
            logger.error { "Signup failed: Identity (CI) already exists" }
            throw UserException(ErrorCode.DUPLICATE_IDENTITY)
        }

        // 2. 엔티티 생성 및 저장
        val user = createUserEntity(request, verifiedCi, verifiedDi, emailHash, ciHash)
        val savedUser = userRepository.save(user)

        logger.info { "User signed up successfully: id=${savedUser.id}" }

        return AuthDto.SignupResponse.from(savedUser, request.email, request.name)
    }

    private fun createUserEntity(
        request: AuthDto.SignupRequest,
        verifiedCi: String,
        verifiedDi: String,
        emailHash: String,
        ciHash: String,
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
            di = verifiedDi,
        )
    }

    @Transactional(noRollbackFor = [UserException::class])
    fun processRefresh(rawToken: String): AuthDto.LoginResponse {
        val now = LocalDateTime.now(ZoneOffset.UTC)

        // 1. DB에서 토큰 조회
        val storedToken = refreshTokenRepository.findByRefreshToken(rawToken)
            ?: throw UserException(ErrorCode.INVALID_TOKEN)

        // 2. 탈취 감지: revoked 토큰 재사용 시도 → token_family 전체 무효화 (만료 체크 전 수행)
        if (storedToken.revoked) {
            refreshTokenRepository
                .findAllByTokenFamilyAndRevokedFalse(storedToken.tokenFamily)
                .forEach { it.revoke(now) }
            logger.error { "Token reuse detected! Family ${storedToken.tokenFamily} fully revoked." }
            throw UserException(ErrorCode.REVOKED_REFRESH_TOKEN)
        }

        // 3. DB 기준 만료 체크
        if (storedToken.expiresAt.isBefore(now))
            throw UserException(ErrorCode.EXPIRED_TOKEN)

        // 4. 사용자 상태 검증 (삭제/휴면 계정은 토큰 즉시 폐기)
        val user = storedToken.user
        if (user.status == UserStatus.DELETED || user.status == UserStatus.DORMANT) {
            storedToken.revoke(now)
            throw UserException(ErrorCode.INVALID_CREDENTIALS)
        }

        // 5. 기존 토큰 폐기 (RTR: 1회용)
        storedToken.revoke(now)

        // 6. 신규 Access + Refresh Token 발급 (같은 tokenFamily 유지)
        val email = encryptionService.decrypt(user.email)
        val (accessToken, refreshToken) = issueTokens(user, email, storedToken.tokenFamily)

        logger.info { "Token refreshed successfully: userId=${user.id}" }

        return AuthDto.LoginResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = Duration.ofMillis(jwtProperties.accessTokenExpiry).seconds,
        )
    }

    private fun issueTokens(user: User, email: String, tokenFamily: UUID = UUID.randomUUID()): Pair<String, String> {
        val (accessToken, accessTokenJti) = jwtTokenProvider.generateAccessToken(user.id!!, user.role, email)
        val (refreshToken, _) = jwtTokenProvider.generateRefreshToken(user.id)

        refreshTokenRepository.save(
            RefreshToken(
                user = user,
                tokenFamily = tokenFamily,
                refreshToken = refreshToken,
                accessTokenJti = accessTokenJti,
                expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(Duration.ofMillis(jwtProperties.refreshTokenExpiry).seconds),
            )
        )

        return Pair(accessToken, refreshToken)
    }
}
