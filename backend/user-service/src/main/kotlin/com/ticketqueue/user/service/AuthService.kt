package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.ExternalSystemException
import com.ticketqueue.user.config.JwtProperties
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.exception.UserException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val recaptchaService: RecaptchaService,
    private val portoneService: PortoneService,
    private val authTransactionalService: AuthTransactionalService,
    private val loginHistoryRecorder: LoginHistoryRecorder,
    private val encryptionService: EncryptionService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val tokenBlacklistService: TokenBlacklistService,
    private val jwtProperties: JwtProperties,
) {
    private val logger = KotlinLogging.logger {}

    fun signup(request: AuthDto.SignupRequest): AuthDto.SignupResponse {
        // 1. reCAPTCHA 검증
        verifyRecaptcha(request.recaptchaToken)

        // 2. PortOne 본인인증 검증 (트랜잭션 외부에서 수행)
        val verifiedCustomer = portoneService.verifyIdentity(request.identityVerificationId)
        val verifiedCi = verifiedCustomer.ci ?: throw UserException(ErrorCode.PORTONE_MISSING_REQUIRED_INFO)
        val verifiedDi = verifiedCustomer.di ?: throw UserException(ErrorCode.PORTONE_MISSING_REQUIRED_INFO)

        // 3. DB 처리 위임 (중복 체크 + 저장)
        return authTransactionalService.processSignup(request, verifiedCi, verifiedDi)
    }

    fun login(
        request: AuthDto.LoginRequest,
        ipAddress: String = "",
        userAgent: String = "",
    ): AuthDto.LoginResponse {
        // 1. reCAPTCHA 검증 (트랜잭션 외부에서 수행, 실패 시 로그인 실패 이력 기록)
        val recaptchaPassed = try {
            recaptchaService.verify(request.recaptchaToken)
        } catch (e: ExternalSystemException) {
            loginHistoryRecorder.recordFailureWithoutUser(ipAddress, userAgent, "RECAPTCHA_SERVICE_ERROR")
            throw e
        }
        if (!recaptchaPassed) {
            logger.error { "reCAPTCHA verification failed during login" }
            loginHistoryRecorder.recordFailureWithoutUser(ipAddress, userAgent, "RECAPTCHA_FAILED")
            throw UserException(ErrorCode.RECAPTCHA_FAILED)
        }

        // 2. DB 처리 위임 (사용자 조회 + 검증 + 토큰 발급)
        val emailHash = encryptionService.hash(request.email)
        return authTransactionalService.processLogin(emailHash, request.password, ipAddress, userAgent)
    }

    fun logout(authorizationHeader: String) {
        if (!authorizationHeader.startsWith("Bearer "))
            throw UserException(ErrorCode.UNAUTHORIZED)
        val accessToken = authorizationHeader.removePrefix("Bearer ")
        val jti = jwtTokenProvider.parseAccessTokenJti(accessToken)
        tokenBlacklistService.addToBlacklist(jti, jwtProperties.accessTokenExpiry)
        authTransactionalService.processLogout(jti)
    }

    fun refresh(request: AuthDto.RefreshRequest): AuthDto.LoginResponse {
        // JWT 서명 검증 (DB 조회 전 빠른 실패)
        jwtTokenProvider.validateAndParseRefreshToken(request.refreshToken)
        return authTransactionalService.processRefresh(request.refreshToken)
    }

    private fun verifyRecaptcha(token: String) {
        if (!recaptchaService.verify(token)) {
            logger.error { "reCAPTCHA verification failed" }
            throw UserException(ErrorCode.RECAPTCHA_FAILED)
        }
    }
}
