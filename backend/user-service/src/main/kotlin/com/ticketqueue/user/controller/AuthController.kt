package com.ticketqueue.user.controller

import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.service.AuthService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${security.trusted-proxy-ips:127.0.0.1,::1}")
    private val trustedProxyIps: String,
    private val authService: AuthService,
) {
    private val logger = KotlinLogging.logger {}

    // 회원가입
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: AuthDto.SignupRequest): AuthDto.SignupResponse {
        logger.info { "Signup request received" }
        return authService.signup(request)
    }

    // 로그인
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: AuthDto.LoginRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<AuthDto.LoginResponse> {
        logger.info { "Login request received" }
        val ipAddress = resolveClientIp(httpRequest)
        val userAgent = httpRequest.getHeader("User-Agent") ?: ""
        return ResponseEntity.ok(authService.login(request, ipAddress, userAgent))
    }

    // 토큰 재발급
    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: AuthDto.RefreshRequest,
    ): ResponseEntity<AuthDto.LoginResponse> {
        logger.info { "Token refresh request received" }
        return ResponseEntity.ok(authService.refresh(request))
    }

    private fun resolveClientIp(request: HttpServletRequest): String {
        val remoteAddr = request.remoteAddr ?: ""
        val trustedIps = trustedProxyIps.split(",").map { it.trim() }
        return if (remoteAddr in trustedIps) {
            request.getHeader("X-Forwarded-For")
                ?.split(",")?.firstOrNull()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: remoteAddr
        } else {
            remoteAddr
        }
    }
}
