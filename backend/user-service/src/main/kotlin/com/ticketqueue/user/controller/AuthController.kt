package com.ticketqueue.user.controller

import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.service.AuthService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: AuthDto.SignupRequest): AuthDto.SignupResponse {
        logger.info { "Signup request received" }

        return authService.signup(request)
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: AuthDto.LoginRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<AuthDto.LoginResponse> {
        logger.info { "Login request received" }

        val ipAddress = httpRequest.getHeader("X-Forwarded-For")
            ?.split(",")?.firstOrNull()?.trim()
            ?: httpRequest.remoteAddr
        val userAgent = httpRequest.getHeader("User-Agent") ?: ""
        return ResponseEntity.ok(authService.login(request, ipAddress, userAgent))
    }
}