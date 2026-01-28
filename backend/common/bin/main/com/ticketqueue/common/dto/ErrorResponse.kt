package com.ticketqueue.common.dto

data class ErrorResponse(
    val code: String,
    val message: String,
    val timestamp: String,
    val traceId: String
)
