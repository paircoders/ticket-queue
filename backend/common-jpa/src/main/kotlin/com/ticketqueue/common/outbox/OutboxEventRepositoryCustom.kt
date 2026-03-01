package com.ticketqueue.common.outbox

import java.time.LocalDateTime

/**
 * Outbox 이벤트 Custom Repository 인터페이스
 * - Querydsl 기반 벌크 삭제 연산 정의
 * - Spring Data JPA 파생 쿼리의 N+1 문제 해결 (SELECT 후 개별 DELETE → 단일 DELETE SQL)
 */
interface OutboxEventRepositoryCustom {
    /** 특정 aggregate 타입의 발행 완료된 이벤트를 일괄 삭제 */
    fun deletePublishedEventsBefore(aggregateType: String, before: LocalDateTime): Long

    /** 모든 aggregate 타입의 발행 완료된 이벤트를 일괄 삭제 */
    fun deleteAllPublishedEventsBefore(before: LocalDateTime): Long
}
