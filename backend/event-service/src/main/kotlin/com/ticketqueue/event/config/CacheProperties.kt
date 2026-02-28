package com.ticketqueue.event.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Redis 캐시 TTL 설정 (REQ-EVT-017)
 *
 * application.yml의 `cache.*` 프로퍼티를 중앙 관리한다.
 * - event.ttl: 공연 목록/상세 캐시 TTL (기본 5분)
 * - schedule.ttl: 회차 상세 캐시 TTL (기본 5분)
 * - seats.ttl: 좌석 재고 통계 캐시 TTL (기본 5분)
 * - layout.ttl: 좌석 배치도 캐시 TTL (기본 24시간 — 홀 구조는 거의 변경되지 않음)
 */
@ConfigurationProperties(prefix = "cache")
data class CacheProperties(
    val event: CacheTtl = CacheTtl(),
    val schedule: CacheTtl = CacheTtl(),
    val seats: CacheTtl = CacheTtl(),
    val layout: CacheTtl = CacheTtl(ttl = 86400L)
) {
    data class CacheTtl(val ttl: Long = 300L)
}
