package com.ticketqueue.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * API Gateway Application
 *
 * Spring Cloud Gateway 기반 API Gateway 진입점
 * - 요청 라우팅
 * - 기본 Resilience4j 통합
 *
 * @see org.springframework.cloud.gateway.config.GatewayAutoConfiguration
 */
@SpringBootApplication
class ApiGatewayApplication

fun main(args: Array<String>) {
    runApplication<ApiGatewayApplication>(*args)
}
