package com.ticketqueue.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val code: String,
    val message: String
) {
    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_001", "Internal server error"),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_002", "Invalid input"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_003", "Resource not found"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_004", "Unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_005", "Access denied"),

    // Auth
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_001", "Invalid token"),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_002", "Token has expired"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_003", "Invalid credentials"),

    // Queue
    QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_001", "Queue is full"),
    ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "QUEUE_002", "Already in queue"),
    QUEUE_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "QUEUE_003", "Queue token expired"),
    QUEUE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "QUEUE_004", "Invalid queue token"),

    // Reservation
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "RSV_001", "Seat is not available"),
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "RSV_002", "Seat is already held"),
    MAX_SEATS_EXCEEDED(HttpStatus.BAD_REQUEST, "RSV_003", "Maximum seats exceeded (max: 4)"),
    HOLD_EXPIRED(HttpStatus.GONE, "RSV_004", "Seat hold has expired"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RSV_005", "Reservation not found"),

    // Payment
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "PAY_001", "Payment failed"),
    PAYMENT_TIMEOUT(HttpStatus.REQUEST_TIMEOUT, "PAY_002", "Payment timeout"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAY_003", "Payment amount mismatch"),
    REFUND_FAILED(HttpStatus.BAD_REQUEST, "PAY_004", "Refund failed"),

    // Event
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVT_001", "Event not found"),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "EVT_002", "Schedule not found"),
    TICKET_SALE_NOT_STARTED(HttpStatus.BAD_REQUEST, "EVT_003", "Ticket sale has not started"),
    TICKET_SALE_ENDED(HttpStatus.BAD_REQUEST, "EVT_004", "Ticket sale has ended")
}
