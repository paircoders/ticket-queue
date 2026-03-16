package com.ticketqueue.common.outbox

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset

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
 * - `outbox.poller.enabled=true` 설정 시에만 폴링 실행 (Producer 서비스만 활성화)
 * - Consumer 전용 서비스(Event Service)는 enabled=false로 설정하여 폴링 스킵
 * - 빈은 항상 생성되지만, enabled=false면 pollAndPublishEvents()가 즉시 반환
 *
 * @see OutboxEvent
 * @see OutboxTopicResolver
 */
@Service
class OutboxPollerService(
    private val queryService: OutboxPollerQueryService,
    private val outboxEventRepository: OutboxEventRepository,
    private val topicResolver: OutboxTopicResolver,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val properties: OutboxPollerProperties
) {
    private val log = LoggerFactory.getLogger(OutboxPollerService::class.java)

    /**
     * 미발행 이벤트를 주기적으로 폴링하여 Kafka에 발행
     *
     * **폴링 주기: 설정 가능 (outbox.poller.fixed-delay, 기본값 1000ms)**
     *
     * **동시성 처리:**
     * - 단일 스레드 폴링 (Spring @Scheduled 기본 동작)
     * - 여러 인스턴스 배포 시 경합 방지: FOR UPDATE SKIP LOCKED (OutboxPollerQueryService)
     * - 각 인스턴스가 서로 다른 이벤트를 조회하여 병렬 처리
     * - Kafka 멱등성(enable.idempotence=true) + Consumer 멱등성으로 중복 발행 방지
     *
     * **조회 조건:**
     * - published=false: 아직 Kafka에 발행되지 않은 이벤트
     * - retryCount < maxRetryCount: 재시도 횟수 초과하지 않은 이벤트
     * - ORDER BY createdAt ASC: 오래된 이벤트부터 처리 (FIFO 보장)
     */
    @Scheduled(fixedDelayString = "\${outbox.poller.fixed-delay:1000}")
    fun pollAndPublish() {
        if (!properties.enabled) {
            return  // 폴러가 비활성화된 경우 실행하지 않음
        }

        val events = queryService.fetchUnpublishedEvents(properties.maxRetryCount, properties.batchSize)

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
     * **Kafka Header에 이벤트 메타데이터 포함 (Phase 1)**
     *
     * **목적:**
     * - Consumer가 JSON 역직렬화 없이 Header만으로 이벤트 타입 식별 가능
     * - CloudEvents 스펙 정합: 메타데이터(type, source)는 Header, 비즈니스 데이터는 Body
     *
     * **Phase 1 전략:**
     * - payload JSON 내 eventType 필드는 하위호환을 위해 유지
     * - 향후 Phase 2에서 Consumer가 Header 기반으로 전환 완료 후 payload 내 중복 제거 예정
     *
     * **Header 목록:**
     * - eventType: 이벤트 종류 (PaymentSuccess, PaymentFailed, ReservationCancelled)
     * - aggregateType: 도메인 객체 (Payment, Reservation)
     * - dlqReason: DLQ 이동 사유 (DLQ 발행 시에만, MAX_RETRY_EXCEEDED)
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
            // - Headers: 이벤트 메타데이터 (eventType, aggregateType)
            val record = ProducerRecord<String, Any>(
                topic, null, event.aggregateId.toString(), event.payload
            ).apply {
                headers().add(RecordHeader("eventType", event.eventType.toByteArray(Charsets.UTF_8)))
                headers().add(RecordHeader("aggregateType", event.aggregateType.toByteArray(Charsets.UTF_8)))
            }
            kafkaTemplate.send(record).get()

            // 발행 성공 시 DB 상태 업데이트
            event.published = true
            event.publishedAt = LocalDateTime.now(ZoneOffset.UTC)
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
     * - retryCount 증가 후 maxRetryCount 미만이면 다음 폴링에서 재시도
     * - 재시도 간격: 폴링 주기 = 즉각 재시도
     *
     * **DLQ 이동 후 처리:**
     * - published=true로 마킹 → 폴링 대상에서 제외
     * - DLQ 토픽에 메시지 발행 → 운영자 수동 확인/재처리
     * - publishedAt 기록 → 정리 배치(7일 후 삭제) 대상 포함
     *
     * **트랜잭션 경계:**
     * - @Transactional: retryCount 업데이트를 원자적으로 처리
     * - 여러 인스턴스가 동일 이벤트 재시도 시 retryCount 경합 가능
     * - 하지만 최악 경우 재시도 횟수 초과 → Consumer 멱등성으로 중복 방지
     *
     * @param event 발행 실패한 Outbox 이벤트
     * @param error 발생한 예외
     */
    @Transactional
    fun handlePublishError(event: OutboxEvent, error: Exception) {
        event.retryCount++
        event.lastError = "${error.javaClass.simpleName}: ${error.message}"

        if (event.retryCount >= properties.maxRetryCount) {
            // 재시도 횟수 초과 → DLQ로 이동
            moveToDlq(event)
            event.published = true // 폴링 대상에서 제외
            event.publishedAt = LocalDateTime.now(ZoneOffset.UTC)

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

            // DLQ 발행 시 추가 헤더(dlqReason) 포함
            val record = ProducerRecord<String, Any>(
                dlqTopic, null, event.aggregateId.toString(), event.payload
            ).apply {
                headers().add(RecordHeader("eventType", event.eventType.toByteArray(Charsets.UTF_8)))
                headers().add(RecordHeader("aggregateType", event.aggregateType.toByteArray(Charsets.UTF_8)))
                headers().add(RecordHeader("dlqReason", "MAX_RETRY_EXCEEDED".toByteArray(Charsets.UTF_8)))
            }
            kafkaTemplate.send(record).get()

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
