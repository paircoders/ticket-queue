package com.ticketqueue.common.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.kafka.KafkaException
import java.util.concurrent.TimeoutException

object ExceptionClassifier {

    private val nonRetryableExceptions: Set<Class<out Exception>> = setOf(
        IllegalArgumentException::class.java,
        IllegalStateException::class.java,
        JsonProcessingException::class.java,
        DataIntegrityViolationException::class.java,
        NullPointerException::class.java,
        ClassCastException::class.java,
    )

    private val retryableExceptions: Set<Class<out Throwable>> = setOf(
        TimeoutException::class.java,
        QueryTimeoutException::class.java,
        KafkaException::class.java,
        PessimisticLockingFailureException::class.java,
    )

    fun isRetryable(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (nonRetryableExceptions.any { it.isInstance(current) }) {
                return false
            }
            if (retryableExceptions.any { it.isInstance(current) }) {
                return true
            }
            current = current.cause
        }
        return true
    }

    fun nonRetryableExceptions(): Set<Class<out Exception>> = nonRetryableExceptions
}
