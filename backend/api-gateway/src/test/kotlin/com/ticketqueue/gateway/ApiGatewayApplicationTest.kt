package com.ticketqueue.gateway

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.config.enabled=false",
        "spring.data.redis.repositories.enabled=false"
    ]
)
@ActiveProfiles("test")
class ApiGatewayApplicationTest {

    @Test
    fun contextLoads() {
        // 컨텍스트 정상 로딩 확인
        // Spring Boot 4.0.2 + Spring Cloud 2025.0.0 호환성 검증
    }
}
