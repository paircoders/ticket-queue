package com.ticketqueue.common.security

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class GatewayAuthFilterTest : DescribeSpec({

    val filter = GatewayAuthFilter()

    afterEach {
        SecurityContextHolder.clearContext()
    }

    fun request(userId: String? = null, userRole: String? = null) =
        MockHttpServletRequest().apply {
            userId?.let { addHeader("X-User-Id", it) }
            userRole?.let { addHeader("X-User-Role", it) }
        }

    fun doFilter(req: MockHttpServletRequest): MockFilterChain {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(req, response, chain)
        return chain
    }

    describe("GatewayAuthFilter") {

        context("X-User-Id, X-User-Role(USER) 헤더가 있는 경우") {
            it("SecurityContext에 ROLE_USER 인증이 설정된다") {
                doFilter(request("user-123", "USER"))
                val auth = SecurityContextHolder.getContext().authentication
                auth shouldNotBe null
                auth.name shouldBe "user-123"
                auth.authorities.map { it.authority } shouldContain "ROLE_USER"
            }
        }

        context("X-User-Id, X-User-Role(ADMIN) 헤더가 있는 경우") {
            it("SecurityContext에 ROLE_ADMIN 인증이 설정된다") {
                doFilter(request("admin-456", "ADMIN"))
                val auth = SecurityContextHolder.getContext().authentication
                auth shouldNotBe null
                auth.name shouldBe "admin-456"
                auth.authorities.map { it.authority } shouldContain "ROLE_ADMIN"
            }
        }

        context("X-User-Id만 있고 X-User-Role 없는 경우") {
            it("SecurityContext에 인증이 설정되지 않는다") {
                doFilter(request(userId = "user-123", userRole = null))
                SecurityContextHolder.getContext().authentication shouldBe null
            }
        }

        context("X-User-Role만 있고 X-User-Id 없는 경우") {
            it("SecurityContext에 인증이 설정되지 않는다") {
                doFilter(request(userId = null, userRole = "USER"))
                SecurityContextHolder.getContext().authentication shouldBe null
            }
        }

        context("두 헤더가 모두 없는 경우") {
            it("SecurityContext에 인증이 설정되지 않는다") {
                doFilter(request())
                SecurityContextHolder.getContext().authentication shouldBe null
            }
        }

        context("X-User-Role이 허용되지 않은 값인 경우 (SUPERADMIN) — 헤더 인젝션 방어") {
            it("SecurityContext에 인증이 설정되지 않는다") {
                doFilter(request("user-123", "SUPERADMIN"))
                SecurityContextHolder.getContext().authentication shouldBe null
            }
        }

        context("filterChain 호출 검증") {
            it("인증 여부와 무관하게 filterChain.doFilter()는 항상 호출된다") {
                val chain = doFilter(request())
                chain.request shouldNotBe null
            }
        }
    }
})
