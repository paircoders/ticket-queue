package com.ticketqueue.user.exception

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode

class UserException(
    errorCode: ErrorCode,
    message: String = errorCode.message,
    cause: Throwable? = null
) : BusinessException(errorCode, message, cause)
