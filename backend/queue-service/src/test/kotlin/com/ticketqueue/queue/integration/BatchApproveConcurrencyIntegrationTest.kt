package com.ticketqueue.queue.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.scheduler.BatchApproveScheduler
import com.ticketqueue.queue.service.QueueService
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CompletableFuture

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("TC-QUEUE-008: 배치 승인 Lua 스크립트 원자성 통합 테스트")
class BatchApproveConcurrencyIntegrationTest {

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
    private lateinit var queueService: QueueService

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        every { eventServiceClient.checkSellable(any()) } returns
            EventServiceClient.SellableResponse(sellable = true, reason = null)
    }

    @Test
    @DisplayName("동시 배치 승인 실행 시 중복 토큰 발급 없음 (Lua 원자성)")
    fun batchApprove_concurrentExecution_noDuplicateTokens() {
        val userCount = 10
        val userIds = (1..userCount).map { UUID.randomUUID() }

        // 10명 순차 진입
        userIds.forEach { uid ->
            mockMvc.perform(
                post("/queue/enter")
                    .header("X-User-Id", uid.toString())
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId.toString())))
            ).andExpect(status().isOk)
        }

        val queueKey = "queue:$scheduleId"
        stringRedisTemplate.opsForZSet().zCard(queueKey) shouldBe userCount.toLong()

        // 2개 스레드에서 동시 batchApprove 호출
        val f1 = CompletableFuture.supplyAsync { queueService.batchApprove(scheduleId) }
        val f2 = CompletableFuture.supplyAsync { queueService.batchApprove(scheduleId) }
        val total = f1.get() + f2.get()

        // 합계 = 정확히 10
        total shouldBe userCount.toLong()

        // Sorted Set 비어 있음
        stringRedisTemplate.opsForZSet().zCard(queueKey) shouldBe 0L

        // user-token 키 정확히 10개
        userIds.forEach { uid ->
            val userTokenKey = "queue:user-token:$uid:$scheduleId"
            stringRedisTemplate.hasKey(userTokenKey) shouldBe true
        }
    }
}
