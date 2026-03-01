package com.ticketqueue.queue.dto

import jakarta.validation.constraints.NotNull
import java.util.UUID

enum class QueueStatus {
    WAITING,  // 대기 중 (대기열 진입 직후 상태)
    ACTIVE    // 대기열 통과 (Queue Token 발급됨)
}

class QueueDto {

    data class EnterRequest(
        @field:NotNull(message = "회차 ID는 필수입니다.")
        val scheduleId: UUID?
    )

    data class EnterResponse(
        val status: QueueStatus,
        val scheduleId: UUID,
        val rank: Long,              // 1-based 대기 순번
        val estimatedWaitTime: Long, // 예상 대기 시간 (초)
        val token: String?           // WAITING 상태에서는 null
    )
}
