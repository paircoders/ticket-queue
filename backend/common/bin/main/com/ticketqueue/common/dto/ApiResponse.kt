package com.ticketqueue.common.dto

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorResponse? = null,
    val timestamp: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)
        fun <T> error(error: ErrorResponse): ApiResponse<T> = ApiResponse(success = false, error = error)
    }
}
