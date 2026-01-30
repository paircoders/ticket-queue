# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**Ticket Queue**는 대규모 트래픽을 처리하는 콘서트 티켓팅 시스템입니다. MSA(마이크로서비스 아키텍처)를 기반으로 하며, 현재 **설계 단계(Design Phase)**에 있습니다.

### 핵심 목표
- K-pop 콘서트와 같은 높은 동시성 환경에서의 공정한 티켓 판매
- Redis 기반 대기열 시스템으로 트래픽 폭주 관리
- 이벤트 기반 아키텍처(Kafka)를 통한 서비스 간 느슨한 결합
- SAGA 패턴과 Transactional Outbox 패턴으로 분산 트랜잭션 관리

## 문서 구조 및 우선순위

모든 아키텍처 및 기능 결정은 `docs/` 디렉토리를 **Source of Truth**로 삼습니다. 코드 작성 전 반드시 다음 문서들을 참조하세요:

### 필수 읽기 순서 (아키텍처)
1. **`docs/REQUIREMENTS.md`** - 110개의 상세 요구사항 (기능 74개/비기능 36개)
2. **`docs/architecture/02_services.md`** - 6개 마이크로서비스의 경계, 책임, API 엔드포인트
3. **`docs/architecture/04_data.md`** - ERD, Redis 데이터 모델, 분산 락 전략
4. **`docs/architecture/05_messaging.md`** - Kafka 토픽 설계, 이벤트 스키마, 멱등성 보장
5. **`docs/architecture/06_api_security.md`** - API Gateway 라우팅, JWT 검증, Rate Limiting
6. **`docs/architecture/07_operations.md`** - 모니터링, 성능 최적화, 테스트 전략

### API 명세서 (specification)
서비스별 상세 API 명세는 `docs/specification/` 디렉토리에서 확인:
- **`00_overview.md`** - 공통 규약 (날짜 형식, 에러 응답, 인증 헤더)
- **`01_user_service.md`** - 회원/인증 API (Auth, Users)
- **`02_event_service.md`** - 공연/공연장 API (Events, Venues, Internal)
- **`03_queue_service.md`** - 대기열 API (Queue)
- **`04_reservation_service.md`** - 예매/좌석 API (Reservations)
- **`05_payment_service.md`** - 결제 API (Payments)

### 공통 규약
- **날짜/시간 형식**: `yyyy-MM-ddTHH:mm:ss` (예: `2026-01-20T10:00:00`)
- **인증 헤더**: `Authorization: Bearer {Access_Token}`
- **대기열 토큰 헤더**: `X-Queue-Token: {Queue_Token}` (예매/결제 API 필수)
- **에러 응답 형식**:
  ```json
  {
    "code": "ERROR_CODE",
    "message": "사용자에게 표시할 메시지",
    "timestamp": "2026-01-20T10:00:00",
    "traceId": "분산 추적용 ID"
  }
  ```

## 마이크로서비스 구조

### 서비스별 책임 및 담당자

| 서비스 | 담당자 | 핵심 책임 | 데이터 스키마 |
|--------|--------|----------|--------------|
| **API Gateway** | A개발자 | 라우팅, JWT 검증, Rate Limiting, Circuit Breaker | 없음 (Stateless) |
| **User Service** | B개발자 | 인증(JWT), 회원관리, OAuth2, reCAPTCHA | `user_service` |
| **Event Service** | A개발자 | 공연/공연장/좌석 관리, Redis 캐싱 | `event_service` |
| **Queue Service** | A개발자 | Redis Sorted Set 기반 대기열, Token 발급 | Redis 전용 |
| **Reservation Service** | B개발자 | 좌석 선점(분산 락), 예매 관리 | `reservation_service` |
| **Payment Service** | B개발자 | PortOne 연동, SAGA 패턴, 보상 트랜잭션 | `payment_service` |

### 데이터베이스 격리 원칙
- **단일 PostgreSQL 인스턴스**에 스키마로 논리적 분리
  ```
  ticketing DB
  ├── user_service (User Service 전용)
  ├── event_service (Event Service 전용)
  ├── reservation_service (Reservation Service 전용)
  ├── payment_service (Payment Service 전용)
  └── common (공통: outbox_events, processed_events)
  ```
- 각 서비스는 자신의 스키마만 접근 (DB 사용자 권한으로 강제)
  - 예: `user_svc_user`는 `user_service` 스키마만 접근 가능
  - 모든 서비스는 `common` 스키마 접근 가능 (Outbox 패턴)
- 서비스 간 데이터 접근은 **REST API** 또는 **Kafka 이벤트**로만 허용
- 직접적인 스키마 간 JOIN 또는 쿼리 금지

## 기술 스택

### Backend
- Java, spring boot 4.0.2 3.5.10, Spring Cloud Gateway
- PostgreSQL 18 (단일 인스턴스, 스키마 분리)
- Redis 7.x (캐시, 대기열, 분산 락)
- Apache Kafka 4.x KRaft 모드 (이벤트 스트리밍, Zookeeper 불필요)
- Redisson (분산 락)
- Resilience4j (Circuit Breaker, Rate Limiter)

### Frontend (미구현)
- Next.js 14+ (Vercel 배포 예정)

### Infrastructure
- AWS: ECS/EKS 타겟, RDS PostgreSQL, ElastiCache Redis, EC2 (Kafka)
- LocalStack (로컬 AWS 모킹)
- Docker & Docker Compose

## 아키텍처 핵심 패턴

### 1. 대기열 시스템 (Queue Service)
- **Redis Sorted Set**: score는 진입 시각(timestamp), member는 userId
  - Key: `queue:{scheduleId}` (공연별이 아닌 회차별 대기열)
- **배치 승인**: 1초마다 10명씩 Lua 스크립트로 원자적 처리 (36,000명/시간)
  - Lua 스크립트로 ZRANGE + ZREM + Token 발급을 원자적으로 처리
- **단일 Queue Token 모델**:
  - `queue_token`: 대기열 통과 후 좌석 조회/선점용 (TTL 10분)
  - Token 데이터: Redis String `queue:token:{token}` (JSON: userId, scheduleId, issuedAt)
  - **결제 권한**: 별도 토큰 없이 `Reservation(PENDING + hold_expires_at)` 기반 검증
    - 결제 시 검증: reservationId 존재 + userId 일치 + status=PENDING + hold_expires_at 미경과
- **중복 대기 방지**: 사용자당 동시 대기 1개 회차만 허용 (Redis `queue:active:{userId}`, TTL 10분)
- **대기열 용량 제한**: 회차당 최대 50,000명 (초과 시 503 Service Unavailable)
- **REQ-QUEUE-001 ~ 011**

### 2. 좌석 선점 및 예매 (Reservation Service)
- **Redisson 분산 락**: `seat:hold:{scheduleId}:{seatId}` (TTL 5분)
  - `tryLock(15초 대기, 300초 보유)` - 경합 시 최대 15초 대기
- **좌석 상태 조회 최적화**:
  - **HOLD 상태**: Redis SET `hold_seats:{scheduleId}` 조회 (O(1), KEYS 명령 금지)
  - **SOLD 상태**: Event Service 내부 API `/internal/seats/status/{scheduleId}` 호출 (Feign)
  - **AVAILABLE**: HOLD도 SOLD도 아닌 좌석
- **좌석 상태 업데이트 책임**:
  - SOLD → RDB: Event Service (ReservationConfirmed 이벤트 수신 시)
  - HOLD 해제: Reservation Service (분산 락 해제) + Event Service (Redis SET 정리)
- **1회 최대 4장 제한**: 좌석 선점 시 개수 검증
- **REQ-RSV-001 ~ 012**

### 3. SAGA 패턴 (Payment Service)
- **Orchestration 방식**: Payment Service가 SAGA 진행 상황 관리
- **성공 플로우**:
  1. 예매 생성 (PENDING) → 2. 결제 성공 → 3. `PaymentSuccess` 이벤트 발행
  4. Reservation Consumer: 예매 확정 (CONFIRMED)
  5. Event Consumer: 좌석 상태 SOLD
- **보상 트랜잭션 (실패 플로우)**:
  1. 예매 생성 (PENDING) → 2. 결제 실패 → 3. `PaymentFailed` 이벤트 발행
  4. Reservation Consumer: 예매 취소 (CANCELLED) → 5. `ReservationCancelled` 이벤트 발행
  6. Event Consumer: 좌석 선점 해제 (AVAILABLE)
- **REQ-PAY-011, REQ-PAY-012**

### 4. Transactional Outbox Pattern
- **문제**: Kafka 발행 실패 시 데이터 불일치
- **해결책**: 비즈니스 로직 트랜잭션 내 `common.outbox_events` 테이블에 이벤트 INSERT
- **Outbox Poller**: 1초마다 미발행 이벤트를 Kafka로 발행 (재시도 최대 3회, 실패 시 DLQ)
- **필수 적용**: Reservation, Payment Service (P0 데이터 정합성 이슈)
- **공통 스키마**: `common.outbox_events` 테이블 (모든 서비스 공유)
  - `id`, `aggregate_type`, `aggregate_id`, `event_type`, `payload` (JSONB)
  - `published` (boolean), `published_at`, `retry_count`, `last_error`
- **정리 배치**: 발행 완료된 이벤트는 7일 후 자동 삭제
- **REQ-RSV-012, REQ-PAY-013**

### 5. 멱등성 보장
- **Producer**: Kafka 자체 멱등성 활성화 (`enable.idempotence=true`, `acks=all`)
- **Consumer (권장 방법)**:
  - **방법 1 (필수)**: `common.processed_events` 테이블에 `event_id` PK + Unique Constraint
    - Consumer 로직 최상단에 먼저 INSERT 시도 (원자적 중복 체크)
    - `DataIntegrityViolationException` 발생 시 이미 처리된 이벤트로 간주하고 종료
    - DB가 Race Condition을 원자적으로 해결 (가장 안전)
  - 방법 2 (보조): Redis `SETNX processed:event:{eventId}` (TTL 7일)
  - 방법 3 (보조): 도메인 키(`paymentKey`, `reservationId`) 기반 중복 체크
- **전달 보장**: At-least-once + Consumer 멱등성 = Exactly-once 효과
- **수동 커밋**: `enable-auto-commit: false`, `ack-mode: MANUAL_IMMEDIATE`
- **REQ-PAY-004, REQ-PAY-010**

### 6. 서비스 간 통신 보안
- **내부 API 패턴**: `/internal/**` 경로는 서비스 간 직접 호출 전용
- **인증**: `X-Service-Api-Key` 헤더로 UUID 기반 API Key 검증
- **API Gateway 차단**: Gateway는 `/internal/**` 라우팅을 명시적으로 차단 (404)
- **네트워크 격리**: VPC Private Subnet 내에서만 접근 가능 (Security Group)
- **예시**: Reservation Service → Event Service `/internal/seats/status/{eventId}` 호출로 SOLD 좌석 조회
- **REQ-INT-001 ~ 010**

## Redis 데이터 모델 핵심

| Key Pattern | 타입 | 용도 | TTL | 서비스 |
|-------------|------|------|-----|--------|
| `queue:{scheduleId}` | Sorted Set | 대기열 (회차별) | 없음 | Queue |
| `queue:token:{token}` | String (JSON) | Queue Token (대기열 통과 후 발급) | 10분 | Queue |
| `queue:active:{userId}` | String | 중복 대기 방지 (scheduleId 저장) | 10분 | Queue |
| `seat:hold:{scheduleId}:{seatId}` | String | 좌석 선점 락 (Redisson) | 5분 | Reservation |
| `hold_seats:{scheduleId}` | Set | HOLD 좌석 ID 목록 (KEYS 대체) | 10분 | Reservation |
| `token:blacklist:{jti}` | String | Access Token 블랙리스트 | 1시간 | User |
| `cache:event:list` | String (JSON) | 공연 목록 캐시 | 5분 | Event |
| `cache:event:{eventId}` | Hash | 공연 메타정보 캐시 | 5분 | Event |
| `cache:schedule:{scheduleId}` | Hash | 회차 상세정보 캐시 | 5분 | Event |
| `cache:seats:{scheduleId}` | Hash | 좌석 정보 캐시 (등급별) | 5분 | Event |

## Kafka 토픽 및 이벤트

### 토픽 목록
| 토픽 | Producer | Consumer | 파티션 수 | 보관 기간 | 용도 |
|------|----------|----------|----------|----------|------|
| `reservation.events` | Reservation | Event | 3 | 3일 | 예매 취소 → 좌석 복구 |
| `payment.events` | Payment | Reservation, Event | 3 | 3일 | 결제 성공/실패 → 예매 확정/취소, 좌석 SOLD |
| `dlq.reservation` | - | Admin | 1 | 7일 | 처리 실패 메시지 (수동 복구) |
| `dlq.payment` | - | Admin | 1 | 7일 | 처리 실패 메시지 (수동 복구) |

### 주요 이벤트 스키마
모든 이벤트는 공통 구조를 따릅니다:
```json
{
  "eventId": "uuid",           // 멱등성 키
  "eventType": "PaymentSuccess | PaymentFailed | ReservationCancelled",
  "aggregateId": "uuid",       // Reservation/Payment ID
  "aggregateType": "string",
  "version": "v1",
  "timestamp": "ISO8601",
  "metadata": {
    "correlationId": "uuid",
    "causationId": "uuid",
    "userId": "uuid"
  },
  "payload": { /* 이벤트별 데이터 */ }
}
```

### Consumer Group 매핑
| Consumer Group ID | 구독 토픽 | 서비스 |
|-------------------|----------|--------|
| `reservation-payment-consumer` | `payment.events` | Reservation Service |
| `event-payment-consumer` | `payment.events` | Event Service |
| `event-reservation-consumer` | `reservation.events` | Event Service |

## 주요 API 엔드포인트 요약

| 서비스 | Method | Endpoint | 설명 | Auth | Queue Token |
|--------|--------|----------|------|:----:|:-----------:|
| User | POST | `/auth/signup` | 회원가입 | - | - |
| User | POST | `/auth/login` | 로그인 | - | - |
| User | POST | `/auth/logout` | 로그아웃 | O | - |
| User | POST | `/auth/refresh` | 토큰 갱신 | - | - |
| User | GET | `/users/me` | 내 프로필 조회 | O | - |
| Event | GET | `/events` | 공연 목록 조회 | - | - |
| Event | GET | `/events/{id}` | 공연 상세 조회 | - | - |
| Event | GET | `/events/schedules/{id}/seats` | 좌석 정보 조회 | - | - |
| Queue | POST | `/queue/enter` | 대기열 진입 | O | - |
| Queue | GET | `/queue/status` | 대기열 상태 조회 | O | - |
| Queue | DELETE | `/queue/leave` | 대기열 이탈 | O | - |
| Reservation | GET | `/reservations/seats/{id}` | 실시간 좌석 상태 | O | O |
| Reservation | POST | `/reservations/hold` | 좌석 선점 | O | O |
| Reservation | GET | `/reservations` | 내 예매 내역 | O | - |
| Payment | POST | `/payments` | 결제 요청 | O | O |
| Payment | POST | `/payments/confirm` | 결제 승인 | O | O |

**내부 API** (서비스 간 통신용, `X-Service-Api-Key` 필수):
- `GET /internal/seats/status/{scheduleId}` - SOLD 좌석 조회 (Event → Reservation)

## 성능 목표 (비기능 요구사항)

- **대기열 진입**: P95 < 100ms (REQ-QUEUE-009)
- **대기열 상태 조회**: P95 < 50ms (REQ-QUEUE-009)
- **공연 목록 조회**: P95 < 200ms (REQ-EVT-004)
- **공연 상세 조회**: P95 < 100ms (REQ-EVT-005)
- **좌석 정보 조회**: P95 < 300ms (REQ-EVT-006)
- **대기열 처리량**: 36,000명/시간 (10명/초) (REQ-QUEUE-005)

## API Gateway 정책

### API Throttling (REQ-GW-005)
- **대기열 토큰**: 주요 API 접근 시 `X-Queue-Token` 필수 검증
- 일반 API: Spring Cloud Gateway 기본 RequestRateLimiter 사용 (선택)

### Timeout (REQ-GW-007, REQ-GW-008)
- Payment 라우트: 60초
- Queue 라우트: 10초
- 기타: 30초 (기본)

### Circuit Breaker (REQ-GW-006)
- 실패율 임계값 초과 시 Circuit Open
- Payment 서비스: 대기 시간 60초

## 보안 요구사항

### 인증/인가
- **JWT**: Access Token (1시간), Refresh Token (7일)
- **로그아웃**: Access Token 블랙리스트 (Redis) + Refresh Token 삭제
- **Refresh Token Rotation (RTR)**: 토큰 갱신 시 신규 Refresh Token 발급 및 기존 토큰 폐기
  - 폐기된 토큰 재사용 시 해당 `token_family` 전체 무효화 (탈취 감지)
- **본인인증**: PortOne CI/DI 수집으로 1인 1계정 강제 (REQ-AUTH-004)
- **reCAPTCHA**: 회원가입 및 로그인 시 봇 차단 (REQ-AUTH-003)

### 암호화
- **비밀번호**: BCrypt 해싱 (REQ-AUTH-014)
- **개인정보**: 평문 저장 (포트폴리오 범위 내 아키텍처 검증 집중)
  - 실제 상용 환경에서는 TDE 또는 컬럼 암호화 필요
- **1인 1계정 강제**: `ci` 컬럼 Unique Constraint

### 개발 가이드라인

### 코드 스타일
- Java 표준 컨벤션 (Google Style Guide 권장)
- Commit Messages: Conventional Commits

### 테스트 전략 (계획)
- **Unit Tests**: JUnit 5, Mockito
- **Integration Tests**: TestContainers (PostgreSQL, Redis, Kafka)
- **Load Testing**: k6 (대기열 및 예매 시나리오 집중)

### 브랜치 전략 (계획)
- Gitflow 또는 Trunk-based development (TBD)

## 주의사항 및 제약사항

### 설계 단계 원칙
1. **문서 우선**: 코드 작성 전 `docs/REQUIREMENTS.md`에서 해당 요구사항 ID(REQ-xxx-xxx) 확인
2. **스키마 격리**: 서비스 간 직접 DB 쿼리 금지, REST API 또는 Kafka 사용
3. **멱등성**: 모든 Kafka Consumer는 멱등성 보장 로직 필수 (`common.processed_events` 테이블 활용)
4. **Outbox 패턴**: 이벤트 발행 시 Transactional Outbox 적용 (Reservation, Payment Service 필수)
5. **내부 API 보안**: `/internal/**` 엔드포인트는 API Key 검증 필수
6. **Redis KEYS 명령 금지**: Production 환경에서 KEYS 명령 사용 금지 (O(N) 복잡도, 전체 DB 스캔)
   - 대신 SET, HASH 등 O(1) 자료구조 사용 (예: `hold_seats:{scheduleId}`)
7. **Queue Token 만료 시 UX**:
   - Queue Token 만료: 401 에러 + 대기열 페이지 리디렉션
   - **결제 권한은 Reservation 기반**: 좌석 선점(PENDING) 후에는 Queue Token 불필요
   - 결제 시 검증: `hold_expires_at` 미경과 여부 확인 (Token과 무관)
   - Frontend Timer 표시로 사용자에게 남은 시간 시각화 권장
8. **DLQ 처리 전략**:
   - 재시도 가능한 예외: 지수 백오프로 최대 3회 재시도 (TimeoutException, 네트워크 오류 등)
   - 재시도 불가능한 예외: 즉시 DLQ 이동 (ValidationException, DataIntegrityViolationException 등)
   - DLQ 모니터링: 메시지 10개 이상 시 알람

### 확장성 고려사항
- **PostgreSQL**: 단일 인스턴스 → Read Replica → 서비스별 물리 분리 (마이그레이션 계획 존재)
- **Redis**: 단일 노드 → Multi-AZ Replication (트래픽 증가 시)
- **Kafka**: 단일 브로커 → 3 브로커 클러스터 (고가용성 전환)

## 외부 서비스 연동

- **PortOne**: 본인인증(CI/DI), 결제 (테스트 모드)
  - Prepare API: 금액 사전 검증 등록
  - Confirm API: 결제 승인 확인 및 금액 검증
  - Timeout: 10초, Circuit Breaker: 실패율 50% 초과 시 Open (60초 대기)
- **reCAPTCHA**: Google reCAPTCHA v2/v3 (봇 차단)
- **OAuth2**: 카카오, 네이버, 구글 (소셜 로그인, 선택)

## 모니터링 및 로깅 (계획)

- **로그**: 구조화된 JSON 로그, CloudWatch Logs 전송
- **메트릭**: CloudWatch Metrics (라우트별 요청 수, 응답 시간, 에러율)
- **알람**: DLQ 메시지 10개 이상, Circuit Breaker Open 시 알림
- **분산 추적**: Request ID 전파 (REQ-GW-010)

## 현재 상태 및 다음 단계

**현재**: 설계 및 문서화 완료 (Draft 1.1.0)
- 110개 요구사항 정의 완료 (필수 74개, 선택 36개)
- 최근 업데이트:
  - **API 명세서 작성 완료**: 6개 서비스별 상세 API 명세 (`docs/specification/`)
  - 데이터 아키텍처: User/Event/Reservation/Payment 스키마 상세 설계 완료
  - 메시징 아키텍처: DLQ 예외 분류 및 재시도 전략 구체화
  - 공통 규약: 날짜/시간 형식, 에러 응답 형식 표준화

**다음 단계**:
1. 각 서비스의 Spring Boot 프로젝트 생성 및 기본 구조 설정
2. PostgreSQL 스키마 및 ERD 기반 JPA Entity 구현
   - `common.outbox_events`, `common.processed_events` 우선 구현
3. Redis 및 Kafka 연동 설정
   - Redisson 분산 락 설정
   - Kafka Producer/Consumer 설정 (멱등성, 수동 커밋)
4. API Gateway 라우팅 및 JWT 검증 필터 구현
5. Queue Service 대기열 로직 구현 (Redis Sorted Set, Lua 스크립트)
6. Reservation Service 분산 락 및 좌석 선점 로직 구현
   - `hold_seats:{scheduleId}` SET 관리 로직
7. Payment Service PortOne 연동 및 SAGA 패턴 구현
   - Outbox Pattern 적용
8. 통합 테스트 및 부하 테스트 (k6)

## 핵심 설계 결정 (ADR 요약)

1. **단일 PostgreSQL 인스턴스**: 비용 효율성, 향후 물리 분리 가능
2. **Redis KEYS 명령 금지**: `hold_seats:{scheduleId}` SET으로 대체
3. **Outbox Pattern 필수**: Reservation, Payment Service (P0 정합성 이슈)
4. **Consumer 멱등성**: `common.processed_events` 테이블 + Unique Constraint (원자적 중복 방지)
5. **단일 Queue Token + Reservation 기반 결제 권한**: qp_token 제거, 결제 권한은 Reservation(PENDING + hold_expires_at) 검증
6. **DLQ 재시도 전략**: 지수 백오프 3회, 재시도 불가능 예외는 즉시 DLQ
7. **보상 토픽 제외**: `payment.events`의 PaymentFailed가 보상 트리거 (YAGNI 원칙)
8. **개인정보 평문 저장**: 암호화 복잡도 제거, 아키텍처/로직 검증 집중 (포트폴리오 최적화)

---

**요약**: 이 프로젝트는 대규모 트래픽을 처리하는 티켓팅 시스템으로, MSA, 이벤트 기반 아키텍처, SAGA 패턴을 적용합니다. 모든 구현은 `docs/` 디렉토리의 요구사항 명세서와 아키텍처 설계서를 기준으로 진행하며, 서비스 간 데이터 격리, 멱등성 보장, 분산 락을 통한 동시성 제어가 핵심입니다.
