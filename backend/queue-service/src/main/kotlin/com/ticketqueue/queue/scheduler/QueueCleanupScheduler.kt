package com.ticketqueue.queue.scheduler

import com.ticketqueue.common.exception.BusinessException
import com.ticketqueue.queue.client.EventServiceClient
import com.ticketqueue.queue.service.QueueService
import feign.RetryableException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 종료된 회차의 대기열 Redis 키 정리 배치 (Issue #125)
 *
 * 매일 03:00 KST 실행:
 * 1. Event Service 내부 API로 종료 + 24시간 경과 회차 ID 목록 조회 (커서 페이지네이션, 전체 처리)
 * 2. 해당 회차의 Redis 키 파이프라인 삭제 (queue:{scheduleId}, active-schedules SREM)
 * 3. 실행 결과 구조화 로깅 및 Micrometer 메트릭 기록
 *
 * Event Service 장애 시: 예외 로그 후 다음 실행 주기까지 대기 (데이터 유실 위험 없음)
 *
 * 수평 확장 시 중복 실행 주의:
 * TODO: 다중 인스턴스 배포 전 ShedLock(@SchedulerLock) 적용 필요. 현재는 단일 EC2 배포이므로 문제없음.
 *       cleanupEndedSchedule은 멱등적(idempotent)이므로 중복 실행해도 데이터 손상 없음.
 */
@Component
class QueueCleanupScheduler(
    private val eventServiceClient: EventServiceClient,
    private val queueService: QueueService,
    private val meterRegistry: MeterRegistry
) {

    private val logger = KotlinLogging.logger {}
    private val slf4jLogger = LoggerFactory.getLogger(QueueCleanupScheduler::class.java)

    private val executedCounter: Counter = meterRegistry.counter("queue.cleanup.executed.total")
    private val deletedCounter: Counter = meterRegistry.counter("queue.cleanup.deleted.total")
    private val failedCounter: Counter = meterRegistry.counter("queue.cleanup.failed.total")
    private val durationTimer: Timer = Timer.builder("queue.cleanup.duration.seconds")
        .description("Queue cleanup batch execution duration")
        .register(meterRegistry)

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    fun executeCleanup() {
        MDC.put("traceId", UUID.randomUUID().toString())
        try {
            durationTimer.record(Runnable { runCleanup() })
        } finally {
            MDC.remove("traceId")
        }
    }

    private fun runCleanup() {
        logger.info { "Queue cleanup batch started" }
        executedCounter.increment()

        val scheduleIds = try {
            eventServiceClient.getEndedScheduleIds().scheduleIds
        } catch (e: RetryableException) {
            failedCounter.increment()
            slf4jLogger.error(
                "Queue cleanup batch failed: Event Service 5xx error (transient): cause={}",
                kv("cause", e.javaClass.simpleName), e
            )
            return
        } catch (e: BusinessException) {
            failedCounter.increment()
            slf4jLogger.error(
                "Queue cleanup batch failed: Event Service 4xx error (non-transient): errorCode={}, cause={}",
                kv("errorCode", e.errorCode), kv("cause", e.javaClass.simpleName), e
            )
            return
        } catch (e: Exception) {
            failedCounter.increment()
            slf4jLogger.error(
                "Queue cleanup batch failed: unable to fetch ended schedules from Event Service: cause={}",
                kv("cause", e.javaClass.simpleName), e
            )
            return
        }

        if (scheduleIds.isEmpty()) {
            logger.info { "Queue cleanup batch completed: no ended schedules to clean up" }
            return
        }

        var deletedCount = 0
        var skippedCount = 0
        var failedCount = 0

        for (scheduleId in scheduleIds) {
            try {
                val deleted = queueService.cleanupEndedSchedule(scheduleId)
                if (deleted) {
                    deletedCount++
                    deletedCounter.increment()
                } else {
                    skippedCount++
                }
                logger.debug { "Queue cleanup: scheduleId=$scheduleId, queueExisted=$deleted" }
            } catch (e: RedisConnectionFailureException) {
                failedCount++
                failedCounter.increment()
                slf4jLogger.error(
                    "Queue cleanup Redis connection failed: scheduleId={}, cause={}",
                    kv("scheduleId", scheduleId), kv("cause", e.javaClass.simpleName), e
                )
            } catch (e: Exception) {
                failedCount++
                failedCounter.increment()
                slf4jLogger.error(
                    "Queue cleanup failed: scheduleId={}, cause={}",
                    kv("scheduleId", scheduleId), kv("cause", e.javaClass.simpleName), e
                )
            }
        }

        slf4jLogger.info(
            "Queue cleanup batch completed: total={}, deleted={}, skipped={}, failed={}",
            kv("total", scheduleIds.size),
            kv("deleted", deletedCount),
            kv("skipped", skippedCount),
            kv("failed", failedCount)
        )
    }
}
