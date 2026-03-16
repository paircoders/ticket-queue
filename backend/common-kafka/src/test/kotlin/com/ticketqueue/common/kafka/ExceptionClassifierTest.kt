package com.ticketqueue.common.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.kafka.KafkaException
import java.util.concurrent.TimeoutException

class ExceptionClassifierTest : DescribeSpec({

    describe("ExceptionClassifier.isRetryable()") {

        context("직접 non-retryable 예외") {
            it("IllegalArgumentException → false") {
                ExceptionClassifier.isRetryable(IllegalArgumentException()) shouldBe false
            }
            it("IllegalStateException → false") {
                ExceptionClassifier.isRetryable(IllegalStateException()) shouldBe false
            }
            it("DataIntegrityViolationException → false") {
                ExceptionClassifier.isRetryable(DataIntegrityViolationException("dup")) shouldBe false
            }
            it("NullPointerException → false") {
                ExceptionClassifier.isRetryable(NullPointerException()) shouldBe false
            }
        }

        context("직접 retryable 예외") {
            it("TimeoutException → true") {
                ExceptionClassifier.isRetryable(TimeoutException()) shouldBe true
            }
            it("QueryTimeoutException → true") {
                ExceptionClassifier.isRetryable(QueryTimeoutException("timeout")) shouldBe true
            }
            it("KafkaException → true") {
                ExceptionClassifier.isRetryable(KafkaException("kafka")) shouldBe true
            }
            it("PessimisticLockingFailureException → true") {
                ExceptionClassifier.isRetryable(PessimisticLockingFailureException("lock")) shouldBe true
            }
        }

        context("cause chain 탐색") {
            it("RuntimeException(cause=DataIntegrityViolationException) → false") {
                val ex = RuntimeException("wrapper", DataIntegrityViolationException("dup"))
                ExceptionClassifier.isRetryable(ex) shouldBe false
            }
            it("RuntimeException(cause=TimeoutException) → true") {
                val ex = RuntimeException("wrapper", TimeoutException())
                ExceptionClassifier.isRetryable(ex) shouldBe true
            }
        }

        context("알 수 없는 예외") {
            it("RuntimeException → true (기본값)") {
                ExceptionClassifier.isRetryable(RuntimeException("unknown")) shouldBe true
            }
        }
    }
})
