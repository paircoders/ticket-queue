package com.ticketqueue.common.exception

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

class GlobalExceptionHandlerTest : DescribeSpec({

    val handler = GlobalExceptionHandler()

    describe("GlobalExceptionHandler") {

        context("BusinessException") {
            it("errorCode의 status와 code를 반환한다") {
                val ex = BusinessException(ErrorCode.UNAUTHORIZED)
                val response = handler.handleBusinessException(ex)
                response.statusCode shouldBe HttpStatus.UNAUTHORIZED
                response.body!!.code shouldBe "UNAUTHORIZED"
                response.body!!.message shouldBe ErrorCode.UNAUTHORIZED.message
            }

            it("커스텀 메시지를 반환한다") {
                val ex = BusinessException(ErrorCode.INVALID_INPUT, "이메일 형식이 잘못되었습니다.")
                val response = handler.handleBusinessException(ex)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body!!.message shouldBe "이메일 형식이 잘못되었습니다."
            }
        }

        context("ExternalSystemException") {
            it("errorCode의 status와 code를 반환한다") {
                val ex = ExternalSystemException(ErrorCode.PORTONE_API_ERROR)
                val response = handler.handleExternalSystemException(ex)
                response.statusCode shouldBe HttpStatus.BAD_GATEWAY
                response.body!!.code shouldBe "PORTONE_API_ERROR"
            }
        }

        context("MethodArgumentNotValidException") {
            it("INVALID_INPUT status와 fieldErrors 메시지를 반환한다") {
                val fieldError = FieldError("obj", "email", "이메일 형식이 아닙니다")
                val bindingResult = mockk<BindingResult>()
                every { bindingResult.fieldErrors } returns listOf(fieldError)

                val ex = mockk<MethodArgumentNotValidException>()
                every { ex.bindingResult } returns bindingResult

                val response = handler.handleMethodArgumentNotValid(ex)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body!!.code shouldBe "INVALID_INPUT"
                response.body!!.message shouldBe "email: 이메일 형식이 아닙니다"
            }
        }

        context("ConstraintViolationException") {
            it("INVALID_INPUT status와 violations 메시지를 반환한다") {
                val path = mockk<Path>()
                every { path.toString() } returns "register.email"

                val violation = mockk<ConstraintViolation<*>>()
                every { violation.propertyPath } returns path
                every { violation.message } returns "이메일 형식이 아닙니다"

                val ex = ConstraintViolationException(setOf(violation))
                val response = handler.handleConstraintViolation(ex)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body!!.code shouldBe "INVALID_INPUT"
                response.body!!.message shouldBe "register.email: 이메일 형식이 아닙니다"
            }
        }

        context("HttpMessageNotReadableException") {
            it("INVALID_INPUT status와 고정 메시지를 반환한다") {
                val ex = mockk<org.springframework.http.converter.HttpMessageNotReadableException>()
                every { ex.message } returns "JSON parse error"

                val response = handler.handleHttpMessageNotReadable(ex)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body!!.code shouldBe "INVALID_INPUT"
                response.body!!.message shouldBe "요청 본문을 읽을 수 없습니다."
            }
        }

        context("MissingServletRequestParameterException") {
            it("INVALID_INPUT status와 파라미터명 포함 메시지를 반환한다") {
                val ex = MissingServletRequestParameterException("page", "int")
                val response = handler.handleMissingServletRequestParameter(ex)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body!!.code shouldBe "INVALID_INPUT"
                response.body!!.message shouldBe "필수 파라미터 'page'이(가) 누락되었습니다."
            }
        }

        context("MethodArgumentTypeMismatchException") {
            it("INVALID_INPUT status와 파라미터명 포함 메시지를 반환한다") {
                val ex = mockk<MethodArgumentTypeMismatchException>()
                every { ex.name } returns "scheduleId"

                val response = handler.handleMethodArgumentTypeMismatch(ex)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
                response.body!!.code shouldBe "INVALID_INPUT"
                response.body!!.message shouldBe "파라미터 'scheduleId'의 값이 올바르지 않습니다."
            }
        }

        context("HttpRequestMethodNotSupportedException") {
            it("METHOD_NOT_ALLOWED status를 반환한다") {
                val ex = HttpRequestMethodNotSupportedException("DELETE")
                val response = handler.handleMethodNotSupported(ex)
                response.statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED
                response.body!!.code shouldBe "METHOD_NOT_ALLOWED"
            }
        }

        context("AccessDeniedException") {
            it("FORBIDDEN status를 반환한다") {
                val ex = AccessDeniedException("접근 불가")
                val response = handler.handleAccessDenied(ex)
                response.statusCode shouldBe HttpStatus.FORBIDDEN
                response.body!!.code shouldBe "FORBIDDEN"
            }
        }

        context("NoResourceFoundException") {
            it("RESOURCE_NOT_FOUND status를 반환한다") {
                val ex = mockk<NoResourceFoundException>()
                every { ex.message } returns "No static resource api/v1/unknown."

                val response = handler.handleNoResourceFound(ex)
                response.statusCode shouldBe HttpStatus.NOT_FOUND
                response.body!!.code shouldBe "RESOURCE_NOT_FOUND"
            }
        }

        context("Exception (fallback)") {
            it("INTERNAL_SERVER_ERROR status를 반환한다") {
                val ex = RuntimeException("예기치 못한 오류")
                val response = handler.handleException(ex)
                response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
                response.body!!.code shouldBe "INTERNAL_SERVER_ERROR"
            }
        }
    }
})
