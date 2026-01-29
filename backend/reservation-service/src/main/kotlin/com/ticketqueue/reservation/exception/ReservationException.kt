package com.ticketqueue.reservation.exception

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode

class ReservationException(
    errorCode: ErrorCode,
    message: String = errorCode.message,
    cause: Throwable? = null
) : BusinessException(errorCode, message, cause)
