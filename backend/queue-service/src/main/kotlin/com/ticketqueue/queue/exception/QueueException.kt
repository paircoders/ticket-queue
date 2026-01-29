package com.ticketqueue.queue.exception

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode

class QueueException(
    errorCode: ErrorCode,
    message: String = errorCode.message,
    cause: Throwable? = null
) : BusinessException(errorCode, message, cause)
