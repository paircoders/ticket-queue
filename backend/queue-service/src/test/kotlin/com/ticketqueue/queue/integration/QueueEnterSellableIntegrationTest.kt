package com.ticketqueue.queue.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.scheduler.BatchApproveScheduler
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
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
@DisplayName("TC-QUEUE-019: 회차 판매 종료 후 대기열 진입 거부 통합 테스트")
class QueueEnterSellableIntegrationTest {

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
    private lateinit var objectMapper: ObjectMapper

    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
    }

    @Test
    @DisplayName("sellable=false, reason=TICKET_SALE_ENDED이면 400 TICKET_SALE_ENDED 반환")
    fun enterQueue_whenTicketSaleEnded_returns400() {
        every { eventServiceClient.checkSellable(any()) } returns
            EventServiceClient.SellableResponse(sellable = false, reason = "TICKET_SALE_ENDED")

        val userId = UUID.randomUUID()

        mockMvc.perform(
            post("/queue/enter")
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId.toString())))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TICKET_SALE_ENDED"))

        // Redis에 진입 흔적 없음
        val queueKey = "queue:$scheduleId"
        val activeKey = "queue:active:$userId"
        assert(!stringRedisTemplate.hasKey(queueKey)) { "queue key should not exist" }
        assert(!stringRedisTemplate.hasKey(activeKey)) { "active key should not exist" }
    }

    @Test
    @DisplayName("sellable=false, reason=TICKET_SALE_NOT_STARTED이면 400 TICKET_SALE_NOT_STARTED 반환")
    fun enterQueue_whenTicketSaleNotStarted_returns400() {
        every { eventServiceClient.checkSellable(any()) } returns
            EventServiceClient.SellableResponse(sellable = false, reason = "TICKET_SALE_NOT_STARTED")

        mockMvc.perform(
            post("/queue/enter")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId.toString())))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TICKET_SALE_NOT_STARTED"))
    }
}
