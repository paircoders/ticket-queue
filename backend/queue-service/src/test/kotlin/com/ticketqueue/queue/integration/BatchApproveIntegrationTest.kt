package com.ticketqueue.queue.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ticketqueue.queue.scheduler.BatchApproveScheduler
import com.ticketqueue.queue.service.QueueService
import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = [
    "spring.cloud.aws.region.static=us-east-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test"
])
@DisplayName("배치 승인 통합 테스트")
class BatchApproveIntegrationTest {

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

    // 자동 스케줄링 비활성화 — 테스트에서 batchApprove를 직접 호출
    @MockitoBean
    private lateinit var batchApproveScheduler: BatchApproveScheduler

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var queueService: QueueService

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    @BeforeEach
    fun flushRedis() {
        requireNotNull(stringRedisTemplate.connectionFactory) { "RedisConnectionFactory is not configured" }
            .connection.use { it.serverCommands().flushAll() }
    }

    private fun enterRequest(uid: UUID = userId, sid: UUID = scheduleId) = post("/queue/enter")
        .header("X-User-Id", uid.toString())
        .header("X-User-Role", "USER")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(mapOf("scheduleId" to sid.toString())))

    private fun statusRequest(uid: UUID = userId, sid: UUID = scheduleId) = get("/queue/status")
        .header("X-User-Id", uid.toString())
        .header("X-User-Role", "USER")
        .param("scheduleId", sid.toString())

    @Test
    @DisplayName("배치 승인 후 queue:token:{token} 키가 생성되고 TTL이 설정된다")
    fun batchApprove_createsTokenKeyWithTtl() {
        mockMvc.perform(enterRequest()).andExpect(status().isOk)

        queueService.batchApprove(scheduleId)

        val userTokenKey = "queue:user-token:$userId:$scheduleId"
        val token = stringRedisTemplate.opsForValue().get(userTokenKey)
        token shouldNotBe null

        val tokenKey = "queue:token:$token"
        stringRedisTemplate.hasKey(tokenKey) shouldBe true

        val ttl = stringRedisTemplate.getExpire(tokenKey, TimeUnit.SECONDS)
        ttl.shouldBeBetween(590L, 600L)
    }

    @Test
    @DisplayName("배치 승인 후 queue:user-token:{userId}:{scheduleId} 키가 생성된다")
    fun batchApprove_createsUserTokenKey() {
        mockMvc.perform(enterRequest()).andExpect(status().isOk)

        queueService.batchApprove(scheduleId)

        val userTokenKey = "queue:user-token:$userId:$scheduleId"
        stringRedisTemplate.hasKey(userTokenKey) shouldBe true

        val ttl = stringRedisTemplate.getExpire(userTokenKey, TimeUnit.SECONDS)
        ttl.shouldBeBetween(590L, 600L)
    }

    @Test
    @DisplayName("배치 승인 후 getQueueStatus가 ACTIVE 상태와 token을 반환한다")
    fun batchApprove_statusBecomesActive() {
        mockMvc.perform(enterRequest()).andExpect(status().isOk)

        queueService.batchApprove(scheduleId)

        mockMvc.perform(statusRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.token").isNotEmpty)
    }

    @Test
    @DisplayName("빈 대기열에 배치 승인 시 0을 반환하고 active-schedules에서 제거된다")
    fun batchApprove_emptyQueue_returnsZeroAndRemovesFromActiveSchedules() {
        // active-schedules에 직접 추가 (대기열 없이)
        stringRedisTemplate.opsForSet().add("queue:active-schedules", scheduleId.toString())

        val approved = queueService.batchApprove(scheduleId)

        approved shouldBe 0L
        stringRedisTemplate.opsForSet().isMember("queue:active-schedules", scheduleId.toString()) shouldBe false
    }

    @Test
    @DisplayName("batchSize보다 적은 사용자가 있으면 실제 수만큼만 토큰을 발급한다")
    fun batchApprove_fewerUsersThanBatchSize_issuesExactCount() {
        val userCount = 3
        val userIds = (1..userCount).map { UUID.randomUUID() }

        // 3명 진입
        userIds.forEach { uid ->
            mockMvc.perform(enterRequest(uid = uid)).andExpect(status().isOk)
        }

        val approved = queueService.batchApprove(scheduleId)

        approved shouldBe userCount.toLong()

        // 각 사용자별 토큰 키 생성 확인
        userIds.forEach { uid ->
            val userTokenKey = "queue:user-token:$uid:$scheduleId"
            stringRedisTemplate.hasKey(userTokenKey) shouldBe true

            // queue:active:{userId} 키 검증
            val activeKey = "queue:active:$uid"
            stringRedisTemplate.hasKey(activeKey) shouldBe true
            stringRedisTemplate.opsForValue().get(activeKey) shouldBe scheduleId.toString()
        }
    }
}
