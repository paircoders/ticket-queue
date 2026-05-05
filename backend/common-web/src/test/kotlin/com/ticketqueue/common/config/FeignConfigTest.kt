package com.ticketqueue.common.config

import com.ticketqueue.common.security.InternalApiKeyValidator
import feign.RequestTemplate
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldNotBe

class FeignConfigTest : DescribeSpec({

    val apiKey = "test-internal-api-key-for-unit-test"
    val internalFeignConfig = InternalFeignConfig(apiKey)

    describe("InternalFeignConfig - RequestInterceptor") {
        context("내부 서비스 간 Feign 요청 전송 시") {
            it("X-Service-Api-Key 헤더가 자동으로 추가된다") {
                val interceptor = internalFeignConfig.internalApiKeyRequestInterceptor()
                val template = RequestTemplate()

                interceptor.apply(template)

                val headers = template.headers()
                headers[InternalApiKeyValidator.HEADER_NAME] shouldNotBe null
                headers[InternalApiKeyValidator.HEADER_NAME]!! shouldContain apiKey
            }

            it("헤더 이름은 X-Service-Api-Key 상수와 일치한다") {
                val interceptor = internalFeignConfig.internalApiKeyRequestInterceptor()
                val template = RequestTemplate()

                interceptor.apply(template)

                template.headers().keys shouldContain InternalApiKeyValidator.HEADER_NAME
            }
        }
    }
})
