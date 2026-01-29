package com.ticketqueue.event.exception

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode

class EventException(
    errorCode: ErrorCode,
    message: String = errorCode.message,
    cause: Throwable? = null
) : BusinessException(errorCode, message, cause)
