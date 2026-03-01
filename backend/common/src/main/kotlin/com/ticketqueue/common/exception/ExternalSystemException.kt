package com.ticketqueue.common.exception

open class ExternalSystemException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)
