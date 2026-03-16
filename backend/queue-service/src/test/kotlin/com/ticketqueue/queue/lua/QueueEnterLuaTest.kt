package com.ticketqueue.queue.lua

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import org.testcontainers.containers.GenericContainer
import java.io.File

class QueueEnterLuaTest : DescribeSpec({

    val valkeyContainer = GenericContainer<Nothing>("valkey/valkey:8.1.5-alpine")
        .apply { withExposedPorts(6379) }

    lateinit var connection: StatefulRedisConnection<String, String>

    val script: String by lazy {
        File("/Users/taekwon/work/project/ticket-queue-199/backend/queue-service/src/main/resources/lua/queue-enter.lua")
            .readText()
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

    fun enterQueue(
        scheduleId: String = "schedule-1",
        userId: String = "user-1",
        maxCapacity: Int = 50000,
        activeUserTtl: Int = 600
    ): List<Long> {
        val keys = arrayOf("queue:$scheduleId", "queue:active:$userId", "queue:active-schedules")
        val argv = arrayOf(
            userId,
            System.currentTimeMillis().toString(),
            maxCapacity.toString(),
            activeUserTtl.toString(),
            scheduleId
        )
        @Suppress("UNCHECKED_CAST")
        return connection.sync().eval(script, ScriptOutputType.MULTI, keys, *argv) as List<Long>
    }

    describe("queue-enter.lua") {

        context("신규 진입 성공") {
            it("[0, 0] 반환") {
                val result = enterQueue()
                result[0] shouldBe 0L
                result[1] shouldBe 0L
            }
        }

        context("동일 회차 중복 진입 (멱등성)") {
            it("[1, rank] 반환") {
                enterQueue()
                val result = enterQueue()
                result[0] shouldBe 1L
                result[1] shouldBe 0L
            }
        }

        context("다른 회차 대기 중") {
            it("[2, otherScheduleId] 반환") {
                enterQueue(scheduleId = "schedule-A", userId = "user-1")
                val result = enterQueue(scheduleId = "schedule-B", userId = "user-1")
                result[0] shouldBe 2L
            }
        }

        context("대기열 가득 참 (maxCapacity=1)") {
            it("[3, -1] 반환") {
                enterQueue(scheduleId = "s1", userId = "user-A", maxCapacity = 1)
                val result = enterQueue(scheduleId = "s1", userId = "user-B", maxCapacity = 1)
                result[0] shouldBe 3L
                result[1] shouldBe -1L
            }
        }

        context("이미 배치 승인 완료 (ZRANK nil)") {
            it("[4, -1] 반환") {
                enterQueue(scheduleId = "s1", userId = "user-1")
                // active 키는 있지만 Sorted Set에서 제거 (배치 승인 시뮬레이션)
                connection.sync().zrem("queue:s1", "user-1")
                val result = enterQueue(scheduleId = "s1", userId = "user-1")
                result[0] shouldBe 4L
                result[1] shouldBe -1L
            }
        }
    }
})
