package com.ticketqueue.queue.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.scheduler.BatchApproveScheduler
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("TC-QUEUE-007: Rate Limit Fail-open 통합 테스트")
class RateLimitFailOpenIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val valkey = GenericContainer("valkey/valkey:8.1-alpine")
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(6379) }
        }
    }

    @MockitoBean
    private lateinit var batchApproveScheduler: BatchApproveScheduler

    @MockkBean
    private lateinit var eventServiceClient: EventServiceClient

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        every { eventServiceClient.checkSellable(any()) } returns
            EventServiceClient.SellableResponse(sellable = true, reason = null)
    }

    @Test
    @DisplayName("Rate Limit Redis 장애 시 상태 조회 요청이 허용된다 (fail-open)")
    fun rateLimit_failOpen_allowsRequestOnRedisError() {
        // 먼저 진입
        mockMvc.perform(
            post("/queue/enter")
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId.toString())))
        ).andExpect(status().isOk)

        // Valkey를 pause하여 Redis 장애 시뮬레이션
        valkey.dockerClient.pauseContainerCmd(valkey.containerId).exec()

        try {
            // 상태 조회 — Redis 장애 시에도 429가 아닌 응답을 반환해야 함 (fail-open)
            val result = mockMvc.perform(
                get("/queue/status")
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Role", "USER")
                    .param("scheduleId", scheduleId.toString())
            ).andReturn()

            val status = result.response.status
            // fail-open: 429(RATE_LIMIT_EXCEEDED)가 아닌 다른 상태코드로 처리됨 (500, 404 등)
            assert(status != 429) { "Expected non-429 status (fail-open), but got $status" }

            // fail-open 카운터 증가 확인
            val counter = meterRegistry.find("queue.ratelimit.failopen.total").counter()
            assert((counter?.count() ?: 0.0) >= 1.0) {
                "Expected queue.ratelimit.failopen.total >= 1, but was ${counter?.count()}"
            }
        } finally {
            valkey.dockerClient.unpauseContainerCmd(valkey.containerId).exec()
        }
    }
}
