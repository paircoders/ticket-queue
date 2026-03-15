package com.ticketqueue.common.security

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import com.ticketqueue.common.exception.GlobalExceptionHandler
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.handler.MappedInterceptor

class InternalApiAuthInterceptorTest : DescribeSpec({

    val validKey = "test-internal-api-key"

    fun interceptor(key: String = validKey): InternalApiAuthInterceptor =
        InternalApiAuthInterceptor(InternalApiKeyValidator(key))

    // --- 단위 테스트: preHandle 직접 호출 ---

    describe("InternalApiAuthInterceptor.preHandle()") {

        context("올바른 키를 헤더에 담아 호출하면") {
            it("예외 없이 true를 반환한다") {
                val request = MockHttpServletRequest().apply {
                    addHeader(InternalApiKeyValidator.HEADER_NAME, validKey)
                }
                val result = shouldNotThrowAny {
                    interceptor().preHandle(request, MockHttpServletResponse(), Any())
                }
                result shouldBe true
            }
        }

        context("헤더가 없으면") {
            it("INTERNAL_API_UNAUTHORIZED BusinessException을 던진다") {
                val ex = shouldThrow<BusinessException> {
                    interceptor().preHandle(MockHttpServletRequest(), MockHttpServletResponse(), Any())
                }
                ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }

        context("잘못된 키를 헤더에 담아 호출하면") {
            it("INTERNAL_API_UNAUTHORIZED BusinessException을 던진다") {
                val request = MockHttpServletRequest().apply {
                    addHeader(InternalApiKeyValidator.HEADER_NAME, "wrong-key")
                }
                val ex = shouldThrow<BusinessException> {
                    interceptor().preHandle(request, MockHttpServletResponse(), Any())
                }
                ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }
    }

    // --- 경로 패턴 통합 테스트: MockMvc standaloneSetup ---

    @RestController
    class StubController {
        @GetMapping("/internal/ping") fun internalPing() = "ok"
        @GetMapping("/api/ping") fun apiPing() = "ok"
    }

    fun buildMockMvc(key: String = validKey): MockMvc =
        MockMvcBuilders.standaloneSetup(StubController())
            // MappedInterceptor가 /internal/** 경로에만 인터셉터를 적용하도록 등록
            .addInterceptors(MappedInterceptor(arrayOf("/internal/**"), interceptor(key)))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    describe("InternalApiWebConfig 경로 패턴 — MockMvc") {

        context("/internal/** 경로에 유효한 키를 전달하면") {
            it("200 OK로 통과한다") {
                buildMockMvc().perform(
                    get("/internal/ping")
                        .header(InternalApiKeyValidator.HEADER_NAME, validKey)
                ).andExpect(status().isOk)
            }
        }

        context("/internal/** 경로에 키 없이 요청하면") {
            it("401 Unauthorized를 반환한다") {
                buildMockMvc().perform(
                    get("/internal/ping")
                ).andExpect(status().isUnauthorized)
            }
        }

        context("/internal/** 경로에 잘못된 키를 전달하면") {
            it("401 Unauthorized를 반환한다") {
                buildMockMvc().perform(
                    get("/internal/ping")
                        .header(InternalApiKeyValidator.HEADER_NAME, "wrong-key")
                ).andExpect(status().isUnauthorized)
            }
        }

        context("/api/** 일반 경로는 키 없이 요청해도") {
            it("인터셉터를 통과하여 200 OK를 반환한다") {
                buildMockMvc().perform(
                    get("/api/ping")
                ).andExpect(status().isOk)
            }
        }
    }
})
