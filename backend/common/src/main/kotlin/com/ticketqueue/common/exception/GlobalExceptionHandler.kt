package com.ticketqueue.common.exception

import com.ticketqueue.common.dto.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(ex: BusinessException): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        log.warn("Business exception occurred: [traceId=$traceId] ${ex.errorCode.code} - ${ex.message}")

        val errorResponse = ErrorResponse(
            code = ex.errorCode.code,
            message = ex.message,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
            traceId = traceId
        )

        return ResponseEntity
            .status(ex.errorCode.status)
            .body(errorResponse)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        val message = ex.bindingResult.fieldErrors
            .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }

        log.warn("Validation exception occurred: [traceId=$traceId] $message")

        val errorResponse = ErrorResponse(
            code = ErrorCode.INVALID_INPUT.code,
            message = message,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
            traceId = traceId
        )

        return ResponseEntity
            .status(ErrorCode.INVALID_INPUT.status)
            .body(errorResponse)
    }

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception): ResponseEntity<ErrorResponse> {
        val traceId = UUID.randomUUID().toString()
        log.error("Unexpected exception occurred: [traceId=$traceId]", ex)

        val errorResponse = ErrorResponse(
            code = ErrorCode.INTERNAL_SERVER_ERROR.code,
            message = ErrorCode.INTERNAL_SERVER_ERROR.message,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
            traceId = traceId
        )

        return ResponseEntity
            .status(ErrorCode.INTERNAL_SERVER_ERROR.status)
            .body(errorResponse)
    }
}