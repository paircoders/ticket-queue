package com.ticketqueue.common.exception

import com.ticketqueue.common.dto.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(ex: BusinessException): ResponseEntity<ErrorResponse> {
        logger.warn { "[${ex.errorCode.code}] ${ex.message}" }
        return ResponseEntity
            .status(ex.errorCode.status)
            .body(ErrorResponse.of(ex.errorCode, ex.message))
    }

    @ExceptionHandler(ExternalSystemException::class)
    fun handleExternalSystemException(ex: ExternalSystemException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "[EXTERNAL_SYSTEM_ERROR] ${ex.message}" }
        return ResponseEntity
            .status(ex.errorCode.status)
            .body(ErrorResponse.of(ex.errorCode, ex.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        logger.warn { "[INVALID_INPUT] $fieldErrors" }
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.status)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, fieldErrors))
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val violations = ex.constraintViolations
            .joinToString(", ") { "${it.propertyPath}: ${it.message}" }
        logger.warn { "[INVALID_INPUT] $violations" }
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.status)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, violations))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        logger.warn { "[INVALID_INPUT] ${ex.message}" }
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.status)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없습니다."))
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(ex: MissingServletRequestParameterException): ResponseEntity<ErrorResponse> {
        val message = "필수 파라미터 '${ex.parameterName}'이(가) 누락되었습니다."
        logger.warn { "[INVALID_INPUT] $message" }
        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.status)
            .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> {
        logger.warn { "[METHOD_NOT_ALLOWED] ${ex.message}" }
        return ResponseEntity
            .status(ErrorCode.METHOD_NOT_ALLOWED.status)
            .body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ErrorResponse> {
        logger.warn { "[FORBIDDEN] ${ex.message}" }
        return ResponseEntity
            .status(ErrorCode.FORBIDDEN.status)
            .body(ErrorResponse.of(ErrorCode.FORBIDDEN))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(ex: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        logger.warn { "[RESOURCE_NOT_FOUND] ${ex.message}" }
        return ResponseEntity
            .status(ErrorCode.RESOURCE_NOT_FOUND.status)
            .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "[INTERNAL_SERVER_ERROR] Unexpected exception" }
        return ResponseEntity
            .status(ErrorCode.INTERNAL_SERVER_ERROR.status)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR))
    }
}
