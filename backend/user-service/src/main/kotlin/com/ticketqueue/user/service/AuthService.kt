package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
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
        if (!recaptchaService.verify(request.recaptchaToken)) {
            logger.error { "reCAPTCHA verification failed during login" }
            loginHistoryRecorder.recordFailureWithoutUser(ipAddress, userAgent, "RECAPTCHA_FAILED")
            throw UserException(ErrorCode.RECAPTCHA_FAILED)
        }

        // 2. DB 처리 위임 (사용자 조회 + 검증 + 토큰 발급)
        return authTransactionalService.processLogin(request.password, ipAddress, userAgent, request.email)
    }

    private fun verifyRecaptcha(token: String) {
        if (!recaptchaService.verify(token)) {
            logger.error { "reCAPTCHA verification failed" }
            throw UserException(ErrorCode.RECAPTCHA_FAILED)
        }
    }
}
