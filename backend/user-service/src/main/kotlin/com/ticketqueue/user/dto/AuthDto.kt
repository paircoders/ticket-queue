package com.ticketqueue.user.dto

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

        @field:NotBlank(message = "CI 정보는 필수입니다.")
        val ci: String,

        @field:NotBlank(message = "DI 정보는 필수입니다.")
        val di: String,

        @field:NotBlank(message = "reCAPTCHA 토큰은 필수입니다.")
        val recaptchaToken: String
    )

    data class SignupResponse(
        val id: UUID,
        val email: String,
        val name: String
    )
}
