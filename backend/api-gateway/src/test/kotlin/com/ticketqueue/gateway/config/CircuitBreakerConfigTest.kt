package com.ticketqueue.gateway.config

import com.ticketqueue.gateway.BaseIntegrationTest
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * CircuitBreaker + TimeLimiter 설정 검증 테스트
 *
 * Resilience4j CircuitBreakerRegistry / TimeLimiterRegistry에
 * 5개 서비스 인스턴스가 application-test.yml에 정의된 설정으로
 * 올바르게 등록되었는지 검증합니다.
 *
 * REQ-GW-006 (Circuit Breaker), REQ-GW-007/008 (Timeout)
 */
class CircuitBreakerConfigTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @Autowired
    private lateinit var timeLimiterRegistry: TimeLimiterRegistry

    companion object {
        private val SERVICE_NAMES = listOf(
            "userService",
            "eventService",
            "queueService",
            "reservationService",
            "paymentService",
        )
    }

    // ─── CircuitBreaker 검증 ───────────────────────────────────────────

    @Test
    fun `5개 CircuitBreaker 인스턴스가 레지스트리에서 생성 가능해야 한다`() {
        SERVICE_NAMES.forEach { name ->
            val cb = circuitBreakerRegistry.circuitBreaker(name)
            cb shouldNotBe null
            cb.name shouldBe name
        }
    }

    @Test
    fun `CircuitBreaker default 설정값이 application-test yml 기준으로 올바르게 적용되어야 한다`() {
        val config = circuitBreakerRegistry.circuitBreaker("userService").circuitBreakerConfig

        // application-test.yml: slidingWindowSize: 10
        config.slidingWindowSize shouldBe 10
        // application-test.yml: failureRateThreshold: 50
        config.failureRateThreshold shouldBe 50f
        // application-test.yml: minimumNumberOfCalls: 3
        config.minimumNumberOfCalls shouldBe 3
    }

    @Test
    fun `모든 서비스가 동일한 default 설정을 공유해야 한다`() {
        SERVICE_NAMES.forEach { name ->
            val config = circuitBreakerRegistry.circuitBreaker(name).circuitBreakerConfig
            config.slidingWindowSize shouldBe 10
            config.failureRateThreshold shouldBe 50f
        }
    }

    // ─── TimeLimiter 검증 ──────────────────────────────────────────────

    @Test
    fun `5개 TimeLimiter 인스턴스가 레지스트리에서 생성 가능해야 한다`() {
        SERVICE_NAMES.forEach { name ->
            val tl = timeLimiterRegistry.timeLimiter(name)
            tl shouldNotBe null
        }
    }

    @Test
    fun `TimeLimiter default timeoutDuration이 5초이어야 한다`() {
        // application-test.yml default: 5s
        val config = timeLimiterRegistry.timeLimiter("userService").timeLimiterConfig
        config.timeoutDuration.toSeconds() shouldBe 5L
    }

    @Test
    fun `queueService TimeLimiter timeoutDuration이 3초이어야 한다`() {
        // application-test.yml queueService override: 3s (운영 10s → 테스트 축소)
        val config = timeLimiterRegistry.timeLimiter("queueService").timeLimiterConfig
        config.timeoutDuration.toSeconds() shouldBe 3L
    }

    @Test
    fun `paymentService TimeLimiter timeoutDuration이 10초이어야 한다`() {
        // application-test.yml paymentService override: 10s (운영 60s → 테스트 축소)
        val config = timeLimiterRegistry.timeLimiter("paymentService").timeLimiterConfig
        config.timeoutDuration.toSeconds() shouldBe 10L
    }

    @Test
    fun `모든 서비스의 cancelRunningFuture가 true이어야 한다`() {
        SERVICE_NAMES.forEach { name ->
            val config = timeLimiterRegistry.timeLimiter(name).timeLimiterConfig
            config.shouldCancelRunningFuture() shouldBe true
        }
    }

    // ─── redisBlacklist CircuitBreaker 검증 ────────────────────────────

    @Test
    fun `redisBlacklist CircuitBreaker 인스턴스가 레지스트리에서 생성 가능해야 한다`() {
        val cb = circuitBreakerRegistry.circuitBreaker("redisBlacklist")
        cb shouldNotBe null
        cb.name shouldBe "redisBlacklist"
    }

    @Test
    fun `redisBlacklist CircuitBreaker 설정값이 application-test yml 기준으로 올바르게 적용되어야 한다`() {
        val config = circuitBreakerRegistry.circuitBreaker("redisBlacklist").circuitBreakerConfig
        // application-test.yml redisBlacklist: slidingWindowSize: 5
        config.slidingWindowSize shouldBe 5
        // application-test.yml redisBlacklist: minimumNumberOfCalls: 3
        config.minimumNumberOfCalls shouldBe 3
        // application-test.yml redisBlacklist: permittedNumberOfCallsInHalfOpenState: 2
        config.permittedNumberOfCallsInHalfOpenState shouldBe 2
        config.slidingWindowType shouldBe CircuitBreakerConfig.SlidingWindowType.COUNT_BASED
    }
}
