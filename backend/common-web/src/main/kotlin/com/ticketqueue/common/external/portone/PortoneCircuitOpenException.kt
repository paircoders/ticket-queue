package com.ticketqueue.common.external.portone

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode

class PortoneCircuitOpenException(
    cause: Throwable? = null
) : BusinessException(ErrorCode.PORTONE_CIRCUIT_OPEN, ErrorCode.PORTONE_CIRCUIT_OPEN.message, cause)
