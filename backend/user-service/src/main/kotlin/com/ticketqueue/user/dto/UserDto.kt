package com.ticketqueue.user.dto

import com.ticketqueue.user.entity.User
import com.ticketqueue.user.entity.UserRole
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.UUID

class UserDto {

    data class ProfileResponse(
        val id: UUID,
        val email: String,
        val name: String,
        val phone: String,
        val role: UserRole,
        val createdAt: LocalDateTime?,
    ) {
        companion object {
            fun of(
                user: User,
                decryptedEmail: String,
                decryptedName: String,
                decryptedPhone: String,
            ): ProfileResponse = ProfileResponse(
                id = user.id!!,
                email = decryptedEmail,
                name = decryptedName,
                phone = decryptedPhone,
                role = user.role,
                createdAt = user.createdAt,
            )
        }
    }

    data class UpdateProfileRequest(
        @field:NotBlank(message = "이름은 필수입니다.")
        @field:Size(min = 1, max = 50, message = "이름은 1~50자여야 합니다.")
        val name: String,

        @field:NotBlank(message = "전화번호는 필수입니다.")
        @field:Pattern(
            regexp = "^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$",
            message = "유효한 전화번호 형식이 아닙니다.",
        )
        val phone: String,
    )

    data class UpdateProfileResponse(
        val id: UUID,
        val name: String,
        val phone: String,
    ) {
        companion object {
            fun of(
                user: User,
                decryptedName: String,
                decryptedPhone: String,
            ): UpdateProfileResponse = UpdateProfileResponse(
                id = user.id!!,
                name = decryptedName,
                phone = decryptedPhone,
            )
        }
    }
}
