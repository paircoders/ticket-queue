package com.ticketqueue.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.common.dto.ErrorResponse
import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import feign.Response
import feign.RetryableException
import feign.codec.ErrorDecoder
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException

class FeignErrorDecoder(
    private val objectMapper: ObjectMapper
) : ErrorDecoder {

    private val logger = KotlinLogging.logger {}
    private val defaultDecoder = ErrorDecoder.Default()

    override fun decode(methodKey: String, response: Response): Exception {
        val status = response.status()

        logger.warn { "[FEIGN_ERROR] methodKey=$methodKey, status=$status, url=${response.request().url()}" }

        val errorResponse = tryParseErrorResponse(response)

        return when {
            status in 400..499 -> handle4xxError(status, errorResponse, methodKey)
            status in 500..599 -> handle5xxError(status, errorResponse, methodKey, response)
            else -> defaultDecoder.decode(methodKey, response)
        }
    }

    private fun handle4xxError(status: Int, errorResponse: ErrorResponse?, methodKey: String): BusinessException {
        logger.error { "[FEIGN_4XX] methodKey=$methodKey, status=$status, code=${errorResponse?.code}" }
        return when (status) {
            401 -> BusinessException(
                ErrorCode.INTERNAL_API_UNAUTHORIZED,
                errorResponse?.message ?: ErrorCode.INTERNAL_API_UNAUTHORIZED.message
            )
            404 -> BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND,
                errorResponse?.message ?: ErrorCode.RESOURCE_NOT_FOUND.message
            )
            else -> BusinessException(
                ErrorCode.INVALID_INPUT,
                errorResponse?.message ?: ErrorCode.INVALID_INPUT.message
            )
        }
    }

    private fun handle5xxError(
        status: Int,
        errorResponse: ErrorResponse?,
        methodKey: String,
        response: Response
    ): RetryableException {
        logger.error { "[FEIGN_5XX] methodKey=$methodKey, status=$status, code=${errorResponse?.code}" }
        return RetryableException(
            status,
            errorResponse?.message ?: "Internal server error from upstream service",
            response.request().httpMethod(),
            null as Long?,
            response.request()
        )
    }

    private fun tryParseErrorResponse(response: Response): ErrorResponse? {
        return try {
            response.body()?.asInputStream()?.use { stream ->
                objectMapper.readValue(stream, ErrorResponse::class.java)
            }
        } catch (e: IOException) {
            logger.debug { "[FEIGN_ERROR_PARSE] Failed to parse error response body: ${e.message}" }
            null
        }
    }
}
