package com.ticketqueue.user.dto

import com.ticketqueue.user.entity.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.util.UUID

class AuthDto {
    data class SignupRequest(
        @field:NotBlank(message = "이메일은 필수입니다.")
        @field:Email(message = "유효한 이메일 형식이 아닙니다.")
        val email: String,

        @field:NotBlank(message = "비밀번호는 필수입니다.")
        val password: String,

        @field:NotBlank(message = "이름은 필수입니다.")
        val name: String,

        @field:NotBlank(message = "전화번호는 필수입니다.")
        val phone: String,

        @field:NotBlank(message = "본인인증 ID는 필수입니다.")
        val identityVerificationId: String,

        @field:NotBlank(message = "reCAPTCHA 토큰은 필수입니다.")
        val recaptchaToken: String
    )

    data class SignupResponse(
        val id: UUID,
        val email: String,
        val name: String
    ) {
        companion object {
            fun from(user: User, rawEmail: String, rawName: String): SignupResponse = SignupResponse(
                id = user.id!!,
                email = rawEmail,
                name = rawName
            )
        }
    }

    data class LoginRequest(
        @field:NotBlank(message = "이메일은 필수입니다.")
        @field:Email(message = "유효한 이메일 형식이 아닙니다.")
        val email: String,

        @field:NotBlank(message = "비밀번호는 필수입니다.")
        val password: String,

        @field:NotBlank(message = "reCAPTCHA 토큰은 필수입니다.")
        val recaptchaToken: String
    )

    data class LoginResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val tokenType: String = "Bearer"
    )

    data class RefreshRequest(
        @field:NotBlank(message = "리프레시 토큰은 필수입니다.")
        val refreshToken: String
    )
}
