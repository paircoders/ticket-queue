package com.ticketqueue.queue.lua

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.core.io.ClassPathResource
import org.testcontainers.containers.GenericContainer
import java.util.UUID

class BatchApproveLuaTest : DescribeSpec({

    val valkeyContainer = GenericContainer<Nothing>("valkey/valkey:8.1.5-alpine")
        .apply { withExposedPorts(6379) }

    lateinit var connection: StatefulRedisConnection<String, String>

    val script: String by lazy {
        ClassPathResource("lua/batch-approve.lua").inputStream
            .bufferedReader()
            .use { it.readText() }
    }

    beforeSpec {
        valkeyContainer.start()
        val client = RedisClient.create("redis://localhost:${valkeyContainer.getMappedPort(6379)}")
        connection = client.connect()
    }

    afterSpec {
        connection.close()
        valkeyContainer.stop()
    }

    afterEach {
        connection.sync().flushall()
    }

    fun batchApprove(
        scheduleId: String = "schedule-1",
        batchSize: Int = 10,
        tokens: List<String> = (1..batchSize).map { "qr_${UUID.randomUUID()}" }
    ): Long {
        val keys = arrayOf("queue:$scheduleId", "queue:active-schedules")
        val baseArgv = arrayOf(
            batchSize.toString(),
            scheduleId,
            "600",
            "600",
            System.currentTimeMillis().toString()
        )
        val argv = baseArgv + tokens.toTypedArray()
        return connection.sync().eval(script, ScriptOutputType.INTEGER, keys, *argv) as Long
    }

    fun addToQueue(scheduleId: String, vararg userIds: String) {
        val now = System.currentTimeMillis().toDouble()
        userIds.forEachIndexed { i, uid ->
            connection.sync().zadd("queue:$scheduleId", now + i, uid)
        }
        connection.sync().sadd("queue:active-schedules", scheduleId)
    }

    describe("batch-approve.lua") {

        context("정상 배치 승인 (3명)") {
            it("3명 승인, 각 token 키 생성 확인") {
                addToQueue("s1", "user-A", "user-B", "user-C")
                val tokens = listOf(
                    "qr_${UUID.randomUUID()}",
                    "qr_${UUID.randomUUID()}",
                    "qr_${UUID.randomUUID()}"
                )
                val count = batchApprove("s1", batchSize = 10, tokens = tokens)
                count shouldBe 3L
                connection.sync().get("queue:token:${tokens[0]}") shouldNotBe null
                connection.sync().get("queue:token:${tokens[1]}") shouldNotBe null
                connection.sync().get("queue:token:${tokens[2]}") shouldNotBe null
            }
        }

        context("qr_ prefix 검증") {
            it("qr_ prefix가 붙은 토큰으로 queue:token:{token} 키가 생성된다") {
                addToQueue("s1", "user-1")
                val token = "qr_${UUID.randomUUID()}"
                batchApprove("s1", batchSize = 1, tokens = listOf(token))
                connection.sync().get("queue:token:$token") shouldNotBe null
            }
        }

        context("빈 대기열") {
            it("0 반환, active-schedules에서 scheduleId 제거") {
                connection.sync().sadd("queue:active-schedules", "s1")
                val count = batchApprove("s1")
                count shouldBe 0L
                connection.sync().sismember("queue:active-schedules", "s1") shouldBe false
            }
        }

        context("batchSize 초과 방어 (대기 10명, batchSize=3)") {
            it("3명만 승인") {
                addToQueue("s1", *Array(10) { "user-$it" })
                val tokens = (1..10).map { "qr_${UUID.randomUUID()}" }
                val count = batchApprove("s1", batchSize = 3, tokens = tokens)
                count shouldBe 3L
            }
        }

        context("TTL 유효성 검증 (tokenTtl=0)") {
            it("에러 반환 (redis.error_reply)") {
                addToQueue("s1", "user-1")
                val keys = arrayOf("queue:s1", "queue:active-schedules")
                val argv = arrayOf(
                    "1", "s1", "0", "600",
                    System.currentTimeMillis().toString(),
                    "qr_token-1"
                )
                try {
                    connection.sync().eval<Any>(script, ScriptOutputType.INTEGER, keys, *argv)
                    throw AssertionError("Should have thrown")
                } catch (e: Exception) {
                    e.message?.contains("INVALID_TTL") shouldBe true
                }
            }
        }
    }
})
