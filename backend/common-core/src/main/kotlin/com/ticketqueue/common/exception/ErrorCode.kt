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
    ALREADY_EXISTS_EMAIL(HttpStatus.CONFLICT, "ALREADY_EXISTS_EMAIL", "이미 사용 중인 이메일입니다."),
    DUPLICATE_IDENTITY(HttpStatus.CONFLICT, "DUPLICATE_IDENTITY", "이미 본인인증이 완료된 다른 계정이 존재합니다."),
    RECAPTCHA_FAILED(HttpStatus.BAD_REQUEST, "RECAPTCHA_FAILED", "reCAPTCHA 검증에 실패했습니다."),
    JWT_CONFIGURATION_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "JWT_CONFIGURATION_ERROR", "서버 설정 오류로 로그인을 처리할 수 없습니다."),
    REVOKED_REFRESH_TOKEN(HttpStatus.FORBIDDEN, "REVOKED_REFRESH_TOKEN", "이미 사용된 토큰입니다. 보안을 위해 재로그인이 필요합니다."),

    // Queue
    QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_FULL", "대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."),
    ALREADY_IN_QUEUE(HttpStatus.CONFLICT, "ALREADY_IN_QUEUE", "이미 대기열에 참여 중입니다."),
    ALREADY_APPROVED(HttpStatus.CONFLICT, "ALREADY_APPROVED", "이미 대기열 승인이 완료되었습니다. 좌석 선택 페이지로 이동해주세요."),
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
    
    // PortOne (Identity Verification)
    PORTONE_VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "PORTONE_VERIFICATION_NOT_FOUND", "본인인증 기록을 찾을 수 없습니다."),
    PORTONE_VERIFICATION_TIMEOUT(HttpStatus.BAD_REQUEST, "PORTONE_VERIFICATION_TIMEOUT", "본인인증 시간이 초과되었거나 완료되지 않았습니다."),
    PORTONE_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "PORTONE_VERIFICATION_FAILED", "본인인증에 실패했습니다."),
    PORTONE_API_ERROR(HttpStatus.BAD_GATEWAY, "PORTONE_API_ERROR", "인증 서비스 서버와의 통신 중 오류가 발생했습니다."),
    RECAPTCHA_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "RECAPTCHA_SERVICE_ERROR", "reCAPTCHA 서비스와의 통신 중 오류가 발생했습니다."),
    PORTONE_MISSING_REQUIRED_INFO(HttpStatus.BAD_REQUEST, "PORTONE_MISSING_REQUIRED_INFO", "본인인증 응답에 필수 정보(CI/DI)가 누락되었습니다."),

    // Internal API
    INTERNAL_API_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "INTERNAL_API_UNAUTHORIZED", "내부 API 인증에 실패했습니다."),

    // Event
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "존재하지 않는 공연입니다."),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "존재하지 않는 공연 회차입니다."),
    TICKET_SALE_NOT_STARTED(HttpStatus.BAD_REQUEST, "TICKET_SALE_NOT_STARTED", "티켓 판매가 아직 시작되지 않았습니다."),
    TICKET_SALE_ENDED(HttpStatus.BAD_REQUEST, "TICKET_SALE_ENDED", "티켓 판매가 종료되었습니다."),
    VENUE_NOT_FOUND(HttpStatus.NOT_FOUND, "VENUE_NOT_FOUND", "존재하지 않는 공연장입니다."),
    VENUE_HAS_HALLS(HttpStatus.CONFLICT, "VENUE_HAS_HALLS", "홀이 존재하는 공연장은 삭제할 수 없습니다."),
    VENUE_HAS_EVENTS(HttpStatus.CONFLICT, "VENUE_HAS_EVENTS", "공연이 존재하는 공연장은 삭제할 수 없습니다."),
    HALL_NOT_FOUND(HttpStatus.NOT_FOUND, "HALL_NOT_FOUND", "존재하지 않는 홀입니다."),
    HALL_NAME_DUPLICATE(HttpStatus.CONFLICT, "HALL_NAME_DUPLICATE", "동일 공연장 내 중복된 홀 이름입니다."),
    HALL_HAS_EVENTS(HttpStatus.CONFLICT, "HALL_HAS_EVENTS", "공연이 존재하는 홀은 삭제할 수 없습니다."),
    INVALID_SEAT_TEMPLATE(HttpStatus.INTERNAL_SERVER_ERROR, "SEAT_TEMPLATE_INVALID", "좌석 템플릿 데이터가 손상되었습니다."),
    INVALID_SEAT_TEMPLATE_MAPPING(HttpStatus.BAD_REQUEST, "SEAT_TEMPLATE_MAPPING_INVALID", "좌석 템플릿의 행-등급 매핑이 올바르지 않습니다."),
    EVENT_HAS_RESERVATIONS(HttpStatus.CONFLICT, "EVENT_HAS_RESERVATIONS", "판매된 좌석이 있는 공연은 삭제할 수 없습니다."),
    EVENT_ALREADY_DELETED(HttpStatus.CONFLICT, "EVENT_ALREADY_DELETED", "이미 삭제된 공연입니다."),
    INVALID_EVENT_STATUS(HttpStatus.BAD_REQUEST, "INVALID_EVENT_STATUS", "유효하지 않은 상태 전환입니다."),
    INVALID_SCHEDULE_TIME(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE_TIME", "회차 시간이 올바르지 않습니다."),
    EVENT_NOT_MODIFIABLE(HttpStatus.CONFLICT, "EVENT_NOT_MODIFIABLE", "판매 시작 후에는 아티스트 정보를 수정할 수 없습니다."),
    DUPLICATE_PLAY_SEQUENCE(HttpStatus.CONFLICT, "DUPLICATE_PLAY_SEQUENCE", "중복된 회차 순번입니다."),
    HALL_NOT_IN_VENUE(HttpStatus.BAD_REQUEST, "HALL_NOT_IN_VENUE", "해당 홀은 선택한 공연장에 속하지 않습니다."),
    INVALID_SCHEDULE_STATUS(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE_STATUS", "유효하지 않은 회차 상태 전환입니다.")
}
