package com.ticketqueue.common.exception

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val code: String,
    val message: String
) {
    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "입력값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "허용되지 않은 HTTP 메서드입니다."),

    // Auth
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_TOKEN", "토큰이 만료되었습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),

    // Queue
    QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_FULL", "대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."),
    ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "ALREADY_IN_QUEUE", "이미 대기열에 참여 중입니다."),
    QUEUE_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "QUEUE_TOKEN_EXPIRED", "대기열 토큰이 만료되었습니다."),
    QUEUE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "QUEUE_TOKEN_INVALID", "유효하지 않은 대기열 토큰입니다."),

    // Reservation
    SEAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "SEAT_NOT_AVAILABLE", "해당 좌석은 선택할 수 없습니다."),
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "SEAT_ALREADY_HELD", "이미 선점된 좌석입니다."),
    MAX_SEATS_EXCEEDED(HttpStatus.BAD_REQUEST, "MAX_SEATS_EXCEEDED", "최대 좌석 수를 초과했습니다. (최대 4석)"),
    HOLD_EXPIRED(HttpStatus.GONE, "HOLD_EXPIRED", "좌석 선점 시간이 만료되었습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "존재하지 않는 예매입니다."),

    // Payment
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "PAYMENT_FAILED", "결제에 실패했습니다."),
    PAYMENT_TIMEOUT(HttpStatus.REQUEST_TIMEOUT, "PAYMENT_TIMEOUT", "결제 처리 시간이 초과되었습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PAYMENT_AMOUNT_MISMATCH", "결제 금액이 일치하지 않습니다."),
    REFUND_FAILED(HttpStatus.BAD_REQUEST, "REFUND_FAILED", "환불에 실패했습니다."),

    // Internal API
    INTERNAL_API_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "INTERNAL_API_UNAUTHORIZED", "내부 API 인증에 실패했습니다."),

    // Event
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "존재하지 않는 공연입니다."),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "존재하지 않는 공연 회차입니다."),
    TICKET_SALE_NOT_STARTED(HttpStatus.BAD_REQUEST, "TICKET_SALE_NOT_STARTED", "티켓 판매가 아직 시작되지 않았습니다."),
    TICKET_SALE_ENDED(HttpStatus.BAD_REQUEST, "TICKET_SALE_ENDED", "티켓 판매가 종료되었습니다.")
}
