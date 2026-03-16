package com.ticketqueue.queue.controller

import com.ticketqueue.queue.dto.QueueDto
import com.ticketqueue.queue.service.QueueService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대기열 관리자 전용 REST 컨트롤러 (REQ-QUEUE-007)
 *
 * API Gateway가 JWT에서 추출한 X-User-Role 헤더를 GatewayAuthFilter가
 * ROLE_ADMIN 권한으로 변환하므로, @PreAuthorize로 ADMIN 전용 접근을 제한한다.
 */
@RestController
@RequestMapping("/queue/admin")
class QueueAdminController(private val queueService: QueueService) {

    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    fun getStats(): ResponseEntity<QueueDto.AdminStatsResponse> {
        return ResponseEntity.ok(queueService.getAdminStats())
    }
}
