package com.ticketqueue.payment.config

import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.common.external.portone.PortoneFeignClient
import com.ticketqueue.common.external.portone.PortoneTokenService
import com.ticketqueue.payment.client.ReservationServiceClient
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration

/**
 * Issue #61 — PortOne CircuitBreaker 설정 검증 (REQ-PAY-009)
 *
 * application.yml 의 `resilience4j.circuitbreaker.instances.portone-v2-client` 설정이
 * Spring Boot autoconfiguration 을 거쳐 [CircuitBreakerRegistry] 에 정확히 반영되는지 확인한다.
 * api-gateway 의 `CircuitBreakerConfigTest` 패턴을 차용한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.config.import=",
        "spring.cloud.aws.region.static=us-east-1",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "spring.cloud.aws.secretsmanager.enabled=false"
    ]
)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("PortOne CircuitBreaker 설정 검증 (REQ-PAY-009)")
class CircuitBreakerConfigTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("ticketing")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/init.sql")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @MockkBean private lateinit var reservationServiceClient: ReservationServiceClient
    @MockkBean private lateinit var portoneClient: PortoneFeignClient
    @MockkBean private lateinit var portoneTokenService: PortoneTokenService

    @Test
    @DisplayName("portone-v2-client CircuitBreaker 인스턴스가 레지스트리에서 조회 가능해야 한다")
    fun instanceExists() {
        val cb = circuitBreakerRegistry.circuitBreaker("portone-v2-client")
        cb shouldNotBe null
        cb.name shouldBe "portone-v2-client"
    }

    @Test
    @DisplayName("portone-v2-client CB 설정값이 application.yml(default 상속) 과 일치해야 한다")
    fun configMatchesYml() {
        val config = circuitBreakerRegistry.circuitBreaker("portone-v2-client").circuitBreakerConfig

        // application.yml default: slidingWindowSize: 100
        config.slidingWindowSize shouldBe 100
        // application.yml default: minimumNumberOfCalls: 5
        config.minimumNumberOfCalls shouldBe 5
        // application.yml default: failureRateThreshold: 50
        config.failureRateThreshold shouldBe 50f
        // application.yml default: permittedNumberOfCallsInHalfOpenState: 3
        config.permittedNumberOfCallsInHalfOpenState shouldBe 3
        // application.yml default: waitDurationInOpenState: 60s — fixed IntervalFunction 은 attempt 와 무관하게 동일 값 반환
        config.waitIntervalFunctionInOpenState.apply(1) shouldBe Duration.ofSeconds(60).toMillis()
    }
}
