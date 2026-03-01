package com.ticketqueue.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import feign.Request
import feign.Response
import feign.RetryableException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.charset.StandardCharsets

class FeignErrorDecoderTest : DescribeSpec({

    val objectMapper = ObjectMapper().registerKotlinModule()
    val decoder = FeignErrorDecoder(objectMapper)
    val methodKey = "EventClient#getSoldSeats(String)"

    fun buildRequest(): Request = Request.create(
        Request.HttpMethod.GET,
        "http://event-service/internal/seats/status/123",
        emptyMap(),
        null,
        StandardCharsets.UTF_8,
        null
    )

    fun buildResponse(status: Int, body: String? = null): Response =
        Response.builder()
            .status(status)
            .request(buildRequest())
            .headers(emptyMap())
            .body(body, StandardCharsets.UTF_8)
            .build()

    describe("FeignErrorDecoder - 4xx 응답") {
        context("401 응답") {
            it("INTERNAL_API_UNAUTHORIZED BusinessException을 throw한다") {
                val ex = decoder.decode(methodKey, buildResponse(401))
                ex.shouldBeInstanceOf<BusinessException>()
                (ex as BusinessException).errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }

        context("404 응답") {
            it("RESOURCE_NOT_FOUND BusinessException을 throw한다") {
                val ex = decoder.decode(methodKey, buildResponse(404))
                ex.shouldBeInstanceOf<BusinessException>()
                (ex as BusinessException).errorCode shouldBe ErrorCode.RESOURCE_NOT_FOUND
            }
        }

        context("400 응답") {
            it("INVALID_INPUT BusinessException을 throw한다") {
                val ex = decoder.decode(methodKey, buildResponse(400))
                ex.shouldBeInstanceOf<BusinessException>()
                (ex as BusinessException).errorCode shouldBe ErrorCode.INVALID_INPUT
            }
        }

        context("403 응답") {
            it("INVALID_INPUT BusinessException을 throw한다") {
                val ex = decoder.decode(methodKey, buildResponse(403))
                ex.shouldBeInstanceOf<BusinessException>()
                (ex as BusinessException).errorCode shouldBe ErrorCode.INVALID_INPUT
            }
        }
    }

    describe("FeignErrorDecoder - 5xx 응답") {
        context("500 응답") {
            it("RetryableException을 throw한다 (재시도 가능)") {
                val ex = decoder.decode(methodKey, buildResponse(500))
                ex.shouldBeInstanceOf<RetryableException>()
                (ex as RetryableException).status() shouldBe 500
            }
        }

        context("503 응답") {
            it("RetryableException을 throw한다 (재시도 가능)") {
                val ex = decoder.decode(methodKey, buildResponse(503))
                ex.shouldBeInstanceOf<RetryableException>()
            }
        }
    }

    describe("FeignErrorDecoder - 응답 body 파싱") {
        context("유효한 ErrorResponse JSON body") {
            it("응답 body의 message를 사용한다") {
                val body = """{"code":"INTERNAL_API_UNAUTHORIZED","message":"내부 API 인증 실패","timestamp":"2026-01-01T00:00:00","traceId":"abc"}"""
                val ex = decoder.decode(methodKey, buildResponse(401, body)) as BusinessException
                ex.message shouldBe "내부 API 인증 실패"
            }
        }

        context("body 파싱 실패 (invalid JSON)") {
            it("ErrorCode 기본 message를 사용한다") {
                val ex = decoder.decode(methodKey, buildResponse(401, "invalid-json")) as BusinessException
                ex.message shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED.message
            }
        }

        context("body 없음") {
            it("ErrorCode 기본 message를 사용한다") {
                val ex = decoder.decode(methodKey, buildResponse(404)) as BusinessException
                ex.message shouldBe ErrorCode.RESOURCE_NOT_FOUND.message
            }
        }

        context("5xx body 파싱") {
            it("응답 body의 message를 RetryableException message로 사용한다") {
                val body = """{"code":"INTERNAL_SERVER_ERROR","message":"서비스 내부 오류","timestamp":"2026-01-01T00:00:00","traceId":"abc"}"""
                val ex = decoder.decode(methodKey, buildResponse(500, body)) as RetryableException
                ex.message shouldBe "서비스 내부 오류"
            }
        }
    }
})
