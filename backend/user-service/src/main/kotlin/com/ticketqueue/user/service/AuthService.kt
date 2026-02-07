package com.ticketqueue.user.service

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.entity.User
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val recaptchaService: RecaptchaService,
    private val encryptionService: EncryptionService
) {

    @Transactional
    fun signup(request: AuthDto.SignupRequest): AuthDto.SignupResponse {
        // 1. reCAPTCHA 검증
        if (!recaptchaService.verify(request.recaptchaToken)) {
            throw UserException(ErrorCode.RECAPTCHA_FAILED)
        }

        // 2. 이메일 중복 체크 (hash 사용)
        val emailHash = encryptionService.hash(request.email)
        if (userRepository.existsByEmailHash(emailHash)) {
            throw UserException(ErrorCode.ALREADY_EXISTS_EMAIL)
        }

        // 3. CI 중복 체크 (hash 사용 - 1인 1계정)
        val ciHash = encryptionService.hash(request.ci)
        if (userRepository.existsByCiHash(ciHash)) {
            throw UserException(ErrorCode.ALREADY_EXISTS_USER)
        }

        // 4. 데이터 암호화 및 해싱
        val encryptedEmail = encryptionService.encrypt(request.email)
        val encryptedName = encryptionService.encrypt(request.name)
        val encryptedPhone = encryptionService.encrypt(request.phone)
        val encryptedCi = encryptionService.encrypt(request.ci)
        
        val phoneHash = encryptionService.hash(request.phone)
        val passwordHash = passwordEncoder.encode(request.password)

        // 5. 엔티티 생성 및 저장
        val user = User(
            email = encryptedEmail,
            emailHash = emailHash,
            passwordHash = passwordHash,
            name = encryptedName,
            phone = encryptedPhone,
            phoneHash = phoneHash,
            ci = encryptedCi,
            ciHash = ciHash,
            di = request.di
        )

        val savedUser = userRepository.save(user)

        return AuthDto.SignupResponse(
            id = savedUser.id!!,
            email = request.email,
            name = request.name
        )
    }
}
