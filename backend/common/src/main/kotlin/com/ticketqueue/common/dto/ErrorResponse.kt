package com.ticketqueue.common.dto

import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.util.DateTimeUtils
import org.slf4j.MDC
import java.util.UUID

data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: String,
    val traceId: String
) {
    companion object {
        fun of(errorCode: ErrorCode, message: String? = null): ErrorResponse {
            return ErrorResponse(
                code = errorCode.code,
                message = message ?: errorCode.message,
                timestamp = DateTimeUtils.now(),
                traceId = resolveTraceId()
            )
        }

        private fun resolveTraceId(): String {
            return MDC.get("traceId") ?: UUID.randomUUID().toString()
        }
    }
}
