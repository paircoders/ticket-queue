package com.ticketqueue.common.outbox

import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID>, OutboxEventRepositoryCustom {

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
     * **동시성 제어 (Scale-out 대비):**
     * - @Lock(PESSIMISTIC_WRITE): SELECT ... FOR UPDATE 적용
     * - lock.timeout=-2: PostgreSQL SKIP LOCKED 활성화
     * - 효과: 다중 인스턴스 환경에서 동일 이벤트 중복 조회 방지
     *   - 인스턴스 A가 조회 중인 행은 인스턴스 B가 건너뜀 (무대기)
     *   - DB 레벨에서 경합 해결 → Kafka 중복 발행 원천 차단
     * - 단일 인스턴스에서도 오버헤드 무시 가능 (PostgreSQL row-level lock)
     *
     * @param maxRetryCount 최대 재시도 횟수 (기본 3)
     * @param pageable 페이징 정보 (크기, 정렬)
     * @return 미발행 이벤트 목록 (최대 pageable.pageSize개)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
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
}
