package com.ticketqueue.common.security

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.common.exception.ErrorCode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.mock.web.MockHttpServletRequest

class InternalApiKeyValidatorTest : DescribeSpec({

    val validKey = "test-internal-api-key"

    fun validator(key: String = validKey) = InternalApiKeyValidator(key)

    fun request(headerValue: String? = null): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            headerValue?.let { addHeader(InternalApiKeyValidator.HEADER_NAME, it) }
        }

    describe("InternalApiKeyValidator.validate()") {

        context("올바른 키를 전달하면") {
            it("예외 없이 통과한다") {
                shouldNotThrowAny { validator().validate(request(validKey)) }
            }
        }

        context("키가 다른 경우") {
            it("INTERNAL_API_UNAUTHORIZED 예외를 던진다") {
                val ex = shouldThrow<BusinessException> {
                    validator().validate(request("wrong-key"))
                }
                ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }

        context("헤더가 없는 경우") {
            it("INTERNAL_API_UNAUTHORIZED 예외를 던진다") {
                val ex = shouldThrow<BusinessException> {
                    validator().validate(request(null))
                }
                ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }

        context("헤더가 빈 문자열인 경우") {
            it("INTERNAL_API_UNAUTHORIZED 예외를 던진다") {
                val ex = shouldThrow<BusinessException> {
                    validator().validate(request(""))
                }
                ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }

        context("내부 API 키가 설정되지 않은 경우 (blank)") {
            it("IllegalStateException을 메시지와 함께 던진다") {
                val ex = shouldThrow<IllegalStateException> {
                    validator("").validate(request(validKey))
                }
                ex.message shouldBe "Internal API key is not configured"
            }
            it("공백 문자열도 IllegalStateException을 던진다") {
                shouldThrow<IllegalStateException> {
                    validator("   ").validate(request(validKey))
                }
            }
        }

        context("Timing Attack 방어 — 길이가 다른 키") {
            it("INTERNAL_API_UNAUTHORIZED 예외를 던진다") {
                val ex = shouldThrow<BusinessException> {
                    validator().validate(request(validKey + "extra"))
                }
                ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }

        context("Timing Attack 방어 — 길이가 같고 내용이 다른 키") {
            it("INTERNAL_API_UNAUTHORIZED 예외를 던진다") {
                val sameLength = validKey.dropLast(1) + "X"
                val ex = shouldThrow<BusinessException> {
                    validator().validate(request(sameLength))
                }
                ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED
            }
        }
    }
})
