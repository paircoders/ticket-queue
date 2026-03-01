package com.ticketqueue.queue.controller

import com.ticketqueue.queue.dto.QueueDto
import com.ticketqueue.queue.service.QueueService
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 대기열 REST 컨트롤러
 *
 * GatewayAuthFilter가 X-User-Id를 Authentication.principal(String)에 설정하므로,
 * authentication.principal을 UUID로 파싱하여 서비스에 전달한다.
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
        val userId = UUID.fromString(authentication.principal as String)
        return queueService.enterQueue(userId, request.scheduleId!!)
    }
}
