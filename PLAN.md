# Issue #202: [Backend] 미구현 요구사항 트래킹 (REQ-GW, REQ-EVT, REQ-QUEUE)

## Overview
BACKEND-REVIEW.md 리뷰에서 식별된 미구현 선택 요구사항(Optional/Enhancement)을 구현합니다.
API Gateway Rate Limiting, Event Service DLQ 처리, Queue Service 관리자 통계 및 성능 모니터링이 주요 작업입니다.
Low 우선순위 항목(REQ-GW-013, REQ-EVT-024)은 백로그로 이동합니다.

## Branch
`feat/202-backend-unimplemented-reqs`

## Checklist

### API Gateway
- [x] REQ-GW-005: `RequestRateLimiter` 필터 + Redis Token Bucket 기반 전역 Rate Limiting 구현
- [x] REQ-GW-018: `/payment/**` 경로 전용 Rate Limiting 강화 설정
- [ ] REQ-GW-013, REQ-EVT-024: 백로그 이슈로 분리 (gzip 압축, 캐시 TTL 동적 조정)

### Event Service
- [x] REQ-EVT-020: DefaultErrorHandler 방식 유지 (MANUAL_IMMEDIATE ack 모드 호환 이슈로 @RetryableTopic 미사용) + 전략 문서화
- [x] REQ-EVT-023: Redis ↔ DB 좌석 재고 정합성 검증 스케줄러 구현 (`SeatConsistencyScheduler`)

### Queue Service
- [x] REQ-QUEUE-007: `GET /queue/admin/stats` 관리자 통계 API 구현 (`QueueAdminController`)
- [x] REQ-QUEUE-009: `queue.enter.time`, `queue.batch.processing.time` Micrometer Timer 설치 (P95 히스토그램 포함)

## Implementation Plan

### Files likely to change

**API Gateway**
- `api-gateway/src/main/resources/application.yml` — Rate Limiting 필터 설정
- `api-gateway/src/main/kotlin/*/config/GatewayConfig.kt` — RedisRateLimiter Bean 설정

**Event Service**
- `event-service/src/main/resources/application.yml` — Kafka retry/DLQ 설정
- `event-service/src/main/kotlin/*/kafka/` — ErrorHandler 또는 @RetryableTopic 설정

**Queue Service**
- `queue-service/src/main/kotlin/*/api/QueueAdminController.kt` — 신규 Admin Stats API
- `queue-service/src/main/kotlin/*/service/QueueService.kt` — 통계 집계 로직
- `queue-service/src/main/kotlin/*/config/MetricsConfig.kt` — Micrometer Timer 등록

### Approach

#### REQ-GW-005 / REQ-GW-018: Rate Limiting
Spring Cloud Gateway `RequestRateLimiterGatewayFilterFactory` + `RedisRateLimiter` 사용.
- 전역: IP 기반 `KeyResolver` → `exchange.request.remoteAddress`
- Payment 경로: 별도 `RouteLocator`에서 더 낮은 replenishRate/burstCapacity 지정
- Valkey(Redis 호환) 기존 인프라 재사용

#### REQ-EVT-020: DLQ 처리
Common 모듈의 `IdempotentConsumerTemplate` 패턴과 일관성 유지.
`@RetryableTopic(attempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))` 방식 검토.
`DataIntegrityViolationException` 등 non-retryable 예외는 즉시 DLT 이동 (기존 Common 모듈 정책 준수).

#### REQ-EVT-023: 재고 정합성 검증
Redis Sorted Set의 남은 좌석 수와 DB `seat.is_available` 필드 비교.
스케줄러(`@Scheduled`) + 불일치 감지 시 Alert 로깅 또는 Redis 보정.

#### REQ-QUEUE-007: Admin Stats API
`/internal/queue/admin/stats` (내부 API, `X-Service-Api-Key` 필수) 또는
`/queue/admin/stats` (Admin Role JWT 필요) — 문서 확인 후 결정.
Redis KEYS 금지 원칙 준수 → 별도 카운터 키(`queue:stats:*`) 활용.

#### REQ-QUEUE-009: Micrometer Timer
`MeterRegistry` 주입 후 대기열 진입/승인 처리 시간 측정.
`management.endpoints.web.exposure.include=prometheus` 설정 확인.
`docs/architecture/07_operations.md` P95 목표값 기준으로 SLO 정의.

## References
- GitHub Issue: #202
- Branch: `feat/202-backend-unimplemented-reqs`
- Worktree path: `/Users/taekwon/work/project/ticket-queue-202`
- Created: 2026-03-16
- 관련 문서:
  - `docs/REQUIREMENTS.md` — REQ-GW-005, REQ-GW-013, REQ-GW-018, REQ-EVT-020, REQ-EVT-023, REQ-EVT-024, REQ-QUEUE-007, REQ-QUEUE-009
  - `docs/architecture/07_operations.md` — 성능 목표 및 모니터링
  - `docs/architecture/05_kafka.md` — DLQ 전략
  - `docs/architecture/06_api_security.md` — 내부 API 보안
