package com.ticketqueue.queue.integration

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
@DisplayName("Queue 대기열 이탈 통합 테스트")
class QueueLeaveIntegrationTest {

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

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun flushRedis() {
        stringRedisTemplate.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun enterRequest(scheduleId: UUID) = post("/queue/enter")
        .header("X-User-Id", userId.toString())
        .header("X-User-Role", "USER")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(mapOf("scheduleId" to scheduleId.toString())))

    private fun leaveRequest(scheduleId: UUID) = delete("/queue/leave")
        .header("X-User-Id", userId.toString())
        .header("X-User-Role", "USER")
        .param("scheduleId", scheduleId.toString())

    private val userId = UUID.randomUUID()
    private val scheduleId = UUID.randomUUID()

    // ── 테스트 1: enter → leave → 200 OK ─────────────────────────────────────

    @Test
    @DisplayName("대기열 진입 후 이탈 시 200 OK와 메시지를 반환한다")
    fun enterThenLeave_returns200() {
        mockMvc.perform(enterRequest(scheduleId))
            .andExpect(status().isOk)

        mockMvc.perform(leaveRequest(scheduleId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Removed from queue"))
    }

    // ── 테스트 2: leave → leave → 404 NOT_IN_QUEUE ───────────────────────────

    @Test
    @DisplayName("이탈 후 재호출 시 404 NOT_IN_QUEUE를 반환한다")
    fun leaveAfterLeave_returns404() {
        mockMvc.perform(enterRequest(scheduleId)).andExpect(status().isOk)
        mockMvc.perform(leaveRequest(scheduleId)).andExpect(status().isOk)

        mockMvc.perform(leaveRequest(scheduleId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_IN_QUEUE"))
    }

    // ── 테스트 3: enter → leave → enter → 재진입 성공 ────────────────────────

    @Test
    @DisplayName("이탈 후 재진입 시 WAITING 상태로 성공한다")
    fun leaveAndReenter_succeeds() {
        mockMvc.perform(enterRequest(scheduleId)).andExpect(status().isOk)
        mockMvc.perform(leaveRequest(scheduleId)).andExpect(status().isOk)

        mockMvc.perform(enterRequest(scheduleId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("WAITING"))
            .andExpect(jsonPath("$.rank").value(1))
    }

    // ── 테스트 4: WAITING 상태 Redis 키 정리 검증 ────────────────────────────

    @Nested
    @DisplayName("Redis 키 정리 검증")
    inner class RedisKeyCleanup {

        @Test
        @DisplayName("WAITING 상태 이탈 후 queue:active와 Sorted Set 멤버가 삭제된다")
        fun leaveQueue_cleansUpRedisKeys_waitingState() {
            val activeKey = "queue:active:$userId"
            val queueKey = "queue:$scheduleId"

            // enter 후 키 존재 확인
            mockMvc.perform(enterRequest(scheduleId)).andExpect(status().isOk)

            stringRedisTemplate.hasKey(activeKey) shouldBe true
            stringRedisTemplate.opsForZSet().rank(queueKey, userId.toString()) shouldBe 0L

            // leave 후 키 삭제 확인
            mockMvc.perform(leaveRequest(scheduleId)).andExpect(status().isOk)

            stringRedisTemplate.hasKey(activeKey) shouldBe false
            stringRedisTemplate.opsForZSet().rank(queueKey, userId.toString()) shouldBe null
        }

        @Test
        @DisplayName("ACTIVE 상태(토큰 발급 후) 이탈 시 queue:active, queue:user-token, queue:token 키가 삭제된다")
        fun leaveQueue_cleansUpRedisKeys_activeState() {
            val activeKey = "queue:active:$userId"
            val userTokenKey = "queue:user-token:$userId:$scheduleId"
            val token = UUID.randomUUID().toString()
            val tokenKey = "queue:token:$token"

            // ACTIVE 상태 시뮬레이션: Redis에 직접 키 세팅 (배치 승인 후 상태)
            stringRedisTemplate.opsForValue().set(activeKey, scheduleId.toString())
            stringRedisTemplate.opsForValue().set(userTokenKey, token)
            stringRedisTemplate.opsForValue().set(tokenKey, """{"userId":"$userId","scheduleId":"$scheduleId"}""")

            // 키 존재 확인
            stringRedisTemplate.hasKey(activeKey) shouldBe true
            stringRedisTemplate.hasKey(userTokenKey) shouldBe true
            stringRedisTemplate.hasKey(tokenKey) shouldBe true

            // leave 호출
            mockMvc.perform(leaveRequest(scheduleId)).andExpect(status().isOk)

            // 모든 토큰 관련 키 삭제 확인
            stringRedisTemplate.hasKey(activeKey) shouldBe false
            stringRedisTemplate.hasKey(userTokenKey) shouldBe false
            stringRedisTemplate.hasKey(tokenKey) shouldBe false
        }
    }
}
