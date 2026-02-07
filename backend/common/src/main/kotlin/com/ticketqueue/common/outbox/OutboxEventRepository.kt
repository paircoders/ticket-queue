package com.ticketqueue.common.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import java.time.LocalDateTime
import java.util.UUID

/**
 * Outbox 이벤트 저장소 (common.outbox_events 테이블)
 *
 * **핵심 역할:**
 * - Transactional Outbox Pattern의 이벤트 영속성 관리
 * - 폴링 대상 조회, 모니터링 통계, 정리 배치 쿼리 제공
 *
 * @see OutboxEvent
 * @see OutboxPollerService
 */
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    /**
     * 미발행 이벤트 조회 (폴링 대상)
     *
     * **조회 조건:**
     * - published=false: 아직 Kafka에 발행되지 않은 이벤트
     * - retryCount < maxRetryCount(3): 재시도 횟수 초과하지 않은 이벤트
     * - ORDER BY createdAt ASC: 오래된 이벤트부터 처리 (FIFO 순서 보장)
     *
     * **페이징:**
     * - Pageable로 배치 크기 제한 (기본 100개)
     * - 한 번에 너무 많은 이벤트 조회 시 메모리 부담 증가 방지
     *
     * **동시성:**
     * - 여러 인스턴스가 동시 조회 시 동일 이벤트 중복 조회 가능
     * - 하지만 Kafka 멱등성 + Consumer 멱등성으로 중복 처리 방지
     *
     * @param maxRetryCount 최대 재시도 횟수 (기본 3)
     * @param pageable 페이징 정보 (크기, 정렬)
     * @return 미발행 이벤트 목록 (최대 pageable.pageSize개)
     */
    fun findByPublishedFalseAndRetryCountLessThanOrderByCreatedAtAsc(
        maxRetryCount: Int,
        pageable: Pageable
    ): List<OutboxEvent>

    /**
     * 특정 Aggregate의 이벤트 조회 (디버깅/모니터링용)
     *
     * **사용 시나리오:**
     * - 특정 결제(Payment) 또는 예매(Reservation)의 이벤트 발행 이력 조회
     * - 예: "결제 ID 12345의 PaymentSuccess 이벤트가 발행되었는가?"
     *
     * **주의:**
     * - 프로덕션 로직에서는 사용 안 함
     * - 운영 도구 또는 테스트 코드에서 사용
     *
     * @param aggregateType 도메인 객체명 (예: "Payment", "Reservation")
     * @param aggregateId 도메인 객체 ID (UUID)
     * @return 해당 Aggregate의 이벤트 목록 (시간순)
     */
    fun findByAggregateTypeAndAggregateId(
        aggregateType: String,
        aggregateId: UUID
    ): List<OutboxEvent>

    /**
     * 발행 완료 이벤트 개수 (디버깅/모니터링용)
     *
     * @return published=true인 이벤트 개수
     */
    fun countByPublishedTrue(): Long

    /**
     * 미발행 이벤트 개수 (디버깅/모니터링용)
     *
     * **주의:**
     * - DLQ 이동된 이벤트는 published=true이므로 제외됨
     * - 실제 "처리 대기 중"인 이벤트만 카운트
     *
     * @return published=false인 이벤트 개수
     */
    fun countByPublishedFalse(): Long

    /**
     * 발행 완료 이벤트 정리 배치 (7일 후 삭제)
     *
     * **정리 정책:**
     * - published=true AND published_at < 7일 전 이벤트 삭제
     * - 이유: Outbox 테이블 크기 무한 증가 방지
     * - 7일 근거: Kafka 토픽 보관 기간(3일)보다 길게 설정 (재처리 여유)
     *
     * **실행 주기:**
     * - 매일 새벽 3시 @Scheduled 배치로 실행 권장
     *
     * @param before 삭제 기준 시각 (이 시각 이전 발행된 이벤트 삭제)
     * @return 삭제된 이벤트 개수
     */
    @Modifying
    fun deleteByPublishedTrueAndPublishedAtBefore(before: LocalDateTime): Int
}
