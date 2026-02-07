package com.ticketqueue.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Transactional Outbox Pattern Poller 서비스
 *
 * **목적:**
 * - 비즈니스 로직과 Kafka 이벤트 발행을 원자적으로 처리하기 위한 Outbox Pattern 구현
 * - DB 트랜잭션 내에서 outbox_events 테이블에 이벤트를 저장한 후, 별도 폴러가 비동기로 Kafka에 발행
 * - Kafka 발행 실패 시에도 데이터 정합성을 보장 (at-least-once 전달)
 *
 * **작동 방식:**
 * 1. 비즈니스 서비스는 DB 트랜잭션 내에서 outbox_events에 이벤트 INSERT
 * 2. 이 폴러가 1초마다 미발행 이벤트(published=false)를 조회
 * 3. Kafka에 발행 성공 시 published=true로 마킹
 * 4. 발행 실패 시 재시도(최대 3회), 초과 시 DLQ로 이동
 *
 * **설정:**
 * - `outbox.poller.enabled=true` 설정 시에만 활성화 (Producer 서비스만 활성화)
 * - Consumer 전용 서비스(Event Service)는 이 빈을 로드하지 않음
 *
 * @see OutboxEvent
 * @see OutboxTopicResolver
 */
@Service
@ConditionalOnProperty(
    name = ["outbox.poller.enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class OutboxPollerService(
    private val outboxEventRepository: OutboxEventRepository,
    private val topicResolver: OutboxTopicResolver,
    @Qualifier("dlqKafkaTemplate")
    private val kafkaTemplate: KafkaTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(OutboxPollerService::class.java)

    companion object {
        /**
         * 최대 재시도 횟수: 3회
         */
        private const val MAX_RETRY_COUNT = 3

        /**
         * 배치 크기: 100개
         *
         * **선택 근거:**
         * - 폴링 주기 1초당 처리량: 최대 100개/초
         */
        private const val BATCH_SIZE = 100
    }

    /**
     * 미발행 이벤트를 주기적으로 폴링하여 Kafka에 발행
     *
     * **폴링 주기: 1초 (fixedDelay = 1000ms)**
     *
     * **동시성 처리:**
     * - 단일 스레드 폴링 (Spring @Scheduled 기본 동작)
     * - 여러 인스턴스 배포 시 경합 가능 (동일 이벤트 중복 발행 시도)
     * - 하지만 Kafka의 멱등성(enable.idempotence=true) + Consumer 멱등성으로 중복 방지
     *
     * **조회 조건:**
     * - published=false: 아직 Kafka에 발행되지 않은 이벤트
     * - retryCount < 3: 재시도 횟수 초과하지 않은 이벤트 (3회 초과는 DLQ 이동됨)
     * - ORDER BY createdAt ASC: 오래된 이벤트부터 처리 (FIFO 보장)
     */
    @Scheduled(fixedDelay = 1000)
    fun pollAndPublish() {
        val events = outboxEventRepository.findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
            MAX_RETRY_COUNT,
            PageRequest.of(0, BATCH_SIZE)
        )

        if (events.isEmpty()) {
            return
        }

        log.debug("Polling {} unpublished outbox events", events.size)

        events.forEach { event ->
            processEvent(event)
        }
    }

    /**
     * 개별 Outbox 이벤트를 처리 (Kafka 발행 + DB 상태 업데이트)
     *
     * **트랜잭션 경계:**
     * - @Transactional: Kafka 발행 성공 시 published=true 업데이트를 원자적으로 처리
     * - Kafka 발행(.get()) 성공 → DB 커밋 → published=true 영구 저장
     * - Kafka 발행 실패 → 예외 발생 → DB 롤백 → published=false 유지 (다음 폴링에서 재시도)
     *
     * **Kafka 발행 방식:**
     * - `.send().get()`: 동기 대기
     * - 왜 동기?: 트랜잭션 커밋 전 발행 성공 여부 확인 필요
     *
     * **멱등성 보장:**
     * - 동일 이벤트 중복 발행 가능 (여러 인스턴스 폴링 경합 시)
     * - Kafka Producer 멱등성(enable.idempotence=true)으로 Kafka 레벨 중복 방지
     * - Consumer 멱등성(processed_events 테이블)으로 처리 레벨 중복 방지
     * - 결과: at-least-once 전달 + exactly-once 효과
     *
     * @param event 발행할 Outbox 이벤트
     */
    @Transactional
    fun processEvent(event: OutboxEvent) {
        try {
            // aggregateType(Payment, Reservation) → Kafka 토픽(payment.events, reservation.events) 매핑
            val topic = topicResolver.resolveTopic(event.aggregateType)

            // Kafka 발행 (동기 대기)
            // - Key: aggregateId (파티셔닝 기준, 동일 Entity는 순서 보장)
            // - Value: payload (JSON 문자열, PaymentSuccess/PaymentFailed/ReservationCancelled 등)
            kafkaTemplate.send(topic, event.aggregateId.toString(), event.payload).get()

            // 발행 성공 시 DB 상태 업데이트
            event.published = true
            event.publishedAt = LocalDateTime.now()
            outboxEventRepository.save(event)

            log.info(
                "Published outbox event: id={}, type={}, topic={}",
                event.id,
                event.eventType,
                topic
            )
        } catch (e: Exception) {
            handlePublishError(event, e)
        }
    }

    /**
     * Kafka 발행 실패 시 에러 처리 (재시도 또는 DLQ 이동)
     *
     * **재시도 전략:**
     * - retryCount 증가 후 MAX_RETRY_COUNT(3) 미만이면 다음 폴링에서 재시도
     * - 재시도 간격: 폴링 주기(1초) = 즉각 재시도
     *
     * **DLQ 이동 후 처리:**
     * - published=true로 마킹 → 폴링 대상에서 제외
     * - DLQ 토픽에 메시지 발행 → 운영자 수동 확인/재처리
     * - publishedAt 기록 → 정리 배치(7일 후 삭제) 대상 포함
     *
     * **트랜잭션 경계:**
     * - @Transactional: retryCount 업데이트를 원자적으로 처리
     * - 여러 인스턴스가 동일 이벤트 재시도 시 retryCount 경합 가능
     * - 하지만 최악 경우 3회 초과 재시도 → Consumer 멱등성으로 중복 방지
     *
     * @param event 발행 실패한 Outbox 이벤트
     * @param error 발생한 예외
     */
    @Transactional
    fun handlePublishError(event: OutboxEvent, error: Exception) {
        event.retryCount++
        event.lastError = "${error.javaClass.simpleName}: ${error.message}"

        if (event.retryCount >= MAX_RETRY_COUNT) {
            // 재시도 횟수 초과 → DLQ로 이동
            moveToDlq(event)
            event.published = true // 폴링 대상에서 제외
            event.publishedAt = LocalDateTime.now()

            log.error(
                "Outbox event exceeded max retries, moved to DLQ: id={}, type={}, retries={}",
                event.id,
                event.eventType,
                event.retryCount
            )
        } else {
            // 재시도 가능 → published=false 유지 (다음 폴링에서 재시도)
            log.warn(
                "Failed to publish outbox event (retry {}): id={}, type={}, error={}",
                event.retryCount,
                event.id,
                event.eventType,
                error.message
            )
        }

        outboxEventRepository.save(event)
    }

    /**
     * 재시도 횟수 초과 이벤트를 DLQ(Dead Letter Queue)로 이동
     *
     * **DLQ 토픽 매핑:**
     * - payment.events → dlq.payment
     * - reservation.events → dlq.reservation
     * - 운영자는 DLQ 메시지를 수동 확인 후 재처리/폐기 결정
     *
     * **DLQ 이동 실패 처리:**
     * - DLQ 발행 실패 시에도 예외를 로그만 남기고 정상 진행
     * - 이유: 원본 이벤트는 published=true로 마킹되어 재폴링 방지
     * - 최악 경우: DLQ 메시지 유실 (운영자가 DB에서 직접 확인 가능)
     *
     * **모니터링 알람:**
     * - DLQ 메시지 10개 이상 누적 시 운영자 알람 권장
     * - CloudWatch Metrics 또는 Kafka Consumer로 DLQ 메시지 수 모니터링
     *
     * @param event DLQ로 이동할 Outbox 이벤트
     */
    private fun moveToDlq(event: OutboxEvent) {
        try {
            val originalTopic = topicResolver.resolveTopic(event.aggregateType)
            val dlqTopic = topicResolver.resolveDlqTopic(originalTopic)
            kafkaTemplate.send(dlqTopic, event.aggregateId.toString(), event.payload).get()

            log.info(
                "Moved outbox event to DLQ: id={}, dlqTopic={}",
                event.id,
                dlqTopic
            )
        } catch (e: Exception) {
            // DLQ 이동 실패 시 로그만 남기고 진행 (published=true로 마킹되어 재폴링 안 됨)
            log.error(
                "Failed to move outbox event to DLQ: id={}, error={}",
                event.id,
                e.message
            )
        }
    }
}
