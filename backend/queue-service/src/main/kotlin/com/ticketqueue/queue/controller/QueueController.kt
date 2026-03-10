package com.ticketqueue.queue.controller

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.queue.dto.QueueDto
import com.ticketqueue.queue.exception.QueueException
import com.ticketqueue.queue.service.QueueService
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 대기열 REST 컨트롤러
 *
 * GatewayAuthFilter가 X-User-Id를 Authentication.principal(String)에 설정하므로,
 * extractUserId() 확장 함수로 UUID를 파싱하여 서비스에 전달한다.
 *
 * 에러 응답은 common 모듈의 GlobalExceptionHandler가 자동 처리한다.
 */
@RestController
@RequestMapping("/queue")
class QueueController(private val queueService: QueueService) {

    @PostMapping("/enter")
    fun enter(
        @Valid @RequestBody request: QueueDto.EnterRequest,
        authentication: Authentication
    ): QueueDto.EnterResponse {
        val userId = authentication.extractUserId()
        val scheduleId = request.scheduleId
            ?: throw QueueException(ErrorCode.INVALID_INPUT, "회차 ID는 필수입니다.")
        return queueService.enterQueue(userId, scheduleId)
    }

    @GetMapping("/status")
    fun status(
        @RequestParam scheduleId: UUID,
        authentication: Authentication
    ): ResponseEntity<QueueDto.StatusResponse> {
        val userId = authentication.extractUserId()
        val response = queueService.getQueueStatus(userId, scheduleId)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(response)
    }

    @DeleteMapping("/leave")
    fun leave(
        @RequestParam scheduleId: UUID,
        authentication: Authentication
    ): QueueDto.LeaveResponse {
        val userId = authentication.extractUserId()
        return queueService.leaveQueue(userId, scheduleId)
    }

    /**
     * GatewayAuthFilter가 설정한 Authentication.principal(String)을 UUID로 파싱한다.
     * 파싱 실패 시 UNAUTHORIZED 예외를 던진다.
     */
    private fun Authentication.extractUserId(): UUID =
        runCatching { UUID.fromString(this.principal as String) }
            .getOrElse { throw QueueException(ErrorCode.UNAUTHORIZED) }
}
