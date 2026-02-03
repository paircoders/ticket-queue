package com.ticketqueue.gateway.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.route.RouteLocator

/**
 * API Gateway 기본 라우트 설정 테스트
 *
 * Issue #12: Spring Cloud Gateway 프로젝트 초기 설정
 * - 5개 서비스 라우트 등록 검증
 */
@SpringBootTest
class GatewayBasicConfigTest {

    @Autowired
    private lateinit var routeLocator: RouteLocator

    @Test
    fun `5개 서비스 라우트가 등록되어 있어야 한다`() {
        // when
        val routes = routeLocator.routes.collectList().block()!!

        // then
        assertThat(routes.size).isEqualTo(5)

        val routeIds = routes.map { it.id }
        assertThat(routeIds).containsExactlyInAnyOrder(
            "user-service",
            "event-service",
            "queue-service",
            "reservation-service",
            "payment-service"
        )
    }

    @Test
    fun `각 라우트의 URI가 올바르게 설정되어 있어야 한다`() {
        // when
        val routes = routeLocator.routes.collectList().block()!!
        val routeMap = routes.associate { it.id to it.uri.toString() }

        // then
        assertThat(routeMap["user-service"]).contains("localhost:8081")
        assertThat(routeMap["event-service"]).contains("localhost:8082")
        assertThat(routeMap["queue-service"]).contains("localhost:8083")
        assertThat(routeMap["reservation-service"]).contains("localhost:8084")
        assertThat(routeMap["payment-service"]).contains("localhost:8085")
    }
}
