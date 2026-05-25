package com.ticketqueue.user.controller

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.user.dto.UserDto
import com.ticketqueue.user.exception.UserException
import com.ticketqueue.user.service.UserService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
) {
    private val logger = KotlinLogging.logger {}

    @GetMapping("/me")
    fun getMyProfile(@AuthenticationPrincipal principal: String?): UserDto.ProfileResponse {
        val userId = parseUserId(principal)
        logger.info { "Profile read: userId=$userId" }
        return userService.getProfile(userId)
    }

    @PatchMapping("/me")
    fun updateMyProfile(
        @AuthenticationPrincipal principal: String?,
        @Valid @RequestBody request: UserDto.UpdateProfileRequest,
    ): UserDto.UpdateProfileResponse {
        val userId = parseUserId(principal)
        logger.info { "Profile update: userId=$userId" }
        return userService.updateProfile(userId, request)
    }

    private fun parseUserId(principal: String?): UUID {
        if (principal.isNullOrBlank()) throw UserException(ErrorCode.UNAUTHORIZED)
        return try {
            UUID.fromString(principal)
        } catch (e: IllegalArgumentException) {
            throw UserException(ErrorCode.UNAUTHORIZED, cause = e)
        }
    }
}
