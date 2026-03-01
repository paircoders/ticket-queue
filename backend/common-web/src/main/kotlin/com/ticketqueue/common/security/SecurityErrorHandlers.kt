package com.ticketqueue.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.dto.ErrorResponse
import com.ticketqueue.common.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * Spring Security 공통 에러 핸들러 팩토리
 *
 * 각 서비스의 SecurityConfig에서 401/403 응답을 프로젝트 표준
 * ErrorResponse 형식으로 반환할 때 사용한다.
 *
 * 사용 예:
 * ```kotlin
 * .exceptionHandling { exceptions ->
 *     exceptions
 *         .authenticationEntryPoint(SecurityErrorHandlers.authenticationEntryPoint(objectMapper))
 *         .accessDeniedHandler(SecurityErrorHandlers.accessDeniedHandler(objectMapper))
 * }
 * ```
 */
object SecurityErrorHandlers {

    /**
     * 401 Unauthorized 에러 핸들러
     *
     * 인증 실패 시 프로젝트 표준 ErrorResponse JSON 형식으로 반환한다.
     */
    fun authenticationEntryPoint(objectMapper: ObjectMapper): AuthenticationEntryPoint {
        return AuthenticationEntryPoint { _: HttpServletRequest, response: HttpServletResponse, _: AuthenticationException ->
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write(objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.UNAUTHORIZED)))
        }
    }

    /**
     * 403 Forbidden 에러 핸들러
     *
     * 인가 실패 시 프로젝트 표준 ErrorResponse JSON 형식으로 반환한다.
     */
    fun accessDeniedHandler(objectMapper: ObjectMapper): AccessDeniedHandler {
        return AccessDeniedHandler { _: HttpServletRequest, response: HttpServletResponse, _: AccessDeniedException ->
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write(objectMapper.writeValueAsString(ErrorResponse.of(ErrorCode.FORBIDDEN)))
        }
    }
}
