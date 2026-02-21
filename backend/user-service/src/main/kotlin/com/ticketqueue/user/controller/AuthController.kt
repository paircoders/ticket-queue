package com.ticketqueue.user.controller

import com.ticketqueue.user.dto.AuthDto
import com.ticketqueue.user.service.AuthService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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
        logger.info { "     ::::: Request to signup ( email : ${request.email}  ) :::::" }
        return authService.signup(request)
    }
}