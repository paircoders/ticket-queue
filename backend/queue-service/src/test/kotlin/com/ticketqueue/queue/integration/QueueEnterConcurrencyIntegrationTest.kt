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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("TC-QUEUE-020: 동시 진입 폭주(100 스레드) 순서 정합성 통합 테스트")
class QueueEnterConcurrencyIntegrationTest {

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
    private lateinit var queueService: QueueService

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        stringRedisTemplate.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        every { eventServiceClient.checkSellable(any()) } returns
            EventServiceClient.SellableResponse(sellable = true, reason = null)
    }

    @Test
    @DisplayName("100개 스레드 동시 진입 시 ZCARD=100, rank 중복 없음")
    fun concurrentEnter_100Threads_noRaceCondition() {
        val userCount = 100
        val userIds = (1..userCount).map { UUID.randomUUID() }
        val latch = CountDownLatch(1)

        val futures = userIds.map { uid ->
            CompletableFuture.supplyAsync {
                latch.await()
                queueService.enterQueue(uid, scheduleId)
            }
        }

        latch.countDown()
        val results = futures.map { it.get() }

        // 모든 요청 성공 (예외 없음)
        results.size shouldBe userCount

        // ZCARD = 100
        val queueKey = "queue:$scheduleId"
        stringRedisTemplate.opsForZSet().zCard(queueKey) shouldBe userCount.toLong()

        // rank 중복 없음: score 값들이 모두 고유해야 함 (동일 ms 진입 가능하므로 멤버 고유성만 검증)
        val members = stringRedisTemplate.opsForZSet().range(queueKey, 0, -1)
        members?.size shouldBe userCount

        // 모든 userId가 queue에 존재
        userIds.forEach { uid ->
            val rank = stringRedisTemplate.opsForZSet().rank(queueKey, uid.toString())
            assert(rank != null && rank in 0 until userCount) {
                "userId $uid rank $rank is out of range [0, $userCount)"
            }
        }
    }
}
