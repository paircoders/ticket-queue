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

### 필수 읽기 순서
1. **`docs/REQUIREMENTS.md`** - 121개의 상세 요구사항 (기능/비기능)
2. **`docs/architecture/02_services.md`** - 6개 마이크로서비스의 경계, 책임, API 엔드포인트
3. **`docs/architecture/04_data.md`** - ERD, Redis 데이터 모델, 분산 락 전략
4. **`docs/architecture/05_messaging.md`** - Kafka 토픽 설계, 이벤트 스키마, 멱등성 보장
5. **`docs/architecture/06_api_security.md`** - API Gateway 라우팅, JWT 검증, Rate Limiting
6. **`docs/architecture/07_domain_logic.md`** - 대기열, 예매, 결제 시스템 상세 설계

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
- 각 서비스는 자신의 스키마만 접근 (DB 사용자 권한으로 강제)
- 서비스 간 데이터 접근은 **REST API** 또는 **Kafka 이벤트**로만 허용
- 직접적인 스키마 간 JOIN 또는 쿼리 금지

## 기술 스택

### Backend
- Java, Spring Boot 4.x, Spring Cloud Gateway
- PostgreSQL 18 (단일 인스턴스, 스키마 분리)
- Redis 7.x (캐시, 대기열, 분산 락)
- Apache Kafka 3.6+ (이벤트 스트리밍)
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
- **배치 승인**: 1초마다 10명씩 Lua 스크립트로 원자적 처리 (36,000명/시간)
- **이중 Token 모델**:
  - `qr_xxx`: Reservation Token (좌석 조회/선점용, TTL 10분)
  - `qp_xxx`: Payment Token (결제용, TTL 10분)
- **중복 대기 방지**: 사용자당 동시 대기 1개 공연만 허용 (Redis `queue:active:{userId}`)
- **REQ-QUEUE-001 ~ 015, 021**

### 2. 좌석 선점 및 예매 (Reservation Service)
- **Redisson 분산 락**: `seat:hold:{eventId}:{seatId}` (TTL 5분)
- **좌석 상태 3단계**:
  - `AVAILABLE`: RDB 조회 (Event Service에서 Redis에 SOLD 없고 RDB에 SOLD 아닌 좌석)
  - `HOLD`: Redis 락 존재 (선점 중, TTL 5분)
  - `SOLD`: RDB 상태 (결제 완료 후 Kafka 이벤트로 업데이트)
- **1회 최대 4장 제한**: 좌석 선점 시 개수 검증
- **REQ-RSV-001 ~ 013**

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
- **Outbox Poller**: 1초마다 미발행 이벤트를 Kafka로 발행 (재시도 3회, 실패 시 DLQ)
- **모든 이벤트 발행에 적용**: Reservation, Payment Service
- **REQ-RSV-012, REQ-PAY-013**

### 5. 멱등성 보장
- **Producer**: Kafka 자체 멱등성 활성화 (`enable.idempotence=true`)
- **Consumer**:
  - 방법 1 (권장): DB `processed_events` 테이블에 `event_id` Unique Constraint
  - 방법 2: Redis `processed:event:{eventId}` SET (7일 TTL)
  - 방법 3: 도메인 키(`paymentKey`, `reservationId`) 기반 중복 체크
- **At-least-once 전달 보장** + Consumer 멱등성으로 Exactly-once 효과
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
| `queue:{eventId}` | Sorted Set | 대기열 | 없음 | Queue |
| `queue:token:{token}` | String | Queue Token | 10분 | Queue |
| `queue:active:{userId}` | String | 중복 대기 방지 | 10분 | Queue |
| `seat:hold:{eventId}:{seatId}` | String | 좌석 선점 락 | 5분 | Reservation |
| `token:blacklist:{token}` | String | Access Token 블랙리스트 | 1시간 | User |
| `cache:event:{eventId}` | Hash | 공연 상세 캐시 | 5분 | Event |
| `cache:seats:{eventId}` | Hash | 좌석 정보 캐시 | 5분 | Event |

## Kafka 토픽 및 이벤트

### 토픽 목록
| 토픽 | Producer | Consumer | 용도 |
|------|----------|----------|------|
| `reservation.events` | Reservation | Event | 예매 취소 → 좌석 복구 |
| `payment.events` | Payment | Reservation, Event | 결제 성공/실패 → 예매 확정/취소, 좌석 SOLD |
| `dlq.reservation` | - | Admin | 처리 실패 메시지 (7일 보관) |
| `dlq.payment` | - | Admin | 처리 실패 메시지 (7일 보관) |

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
    "userId": "uuid"
  },
  "payload": { /* 이벤트별 데이터 */ }
}
```

## 성능 목표 (비기능 요구사항)

- **대기열 진입**: P95 < 100ms (REQ-QUEUE-015)
- **대기열 상태 조회**: P95 < 50ms (REQ-QUEUE-015)
- **공연 목록 조회**: P95 < 200ms (REQ-EVT-004)
- **공연 상세 조회**: P95 < 100ms (REQ-EVT-005)
- **좌석 정보 조회**: P95 < 300ms (REQ-EVT-006)
- **대기열 처리량**: 36,000명/시간 (10명/초) (REQ-QUEUE-005)

## API Gateway 정책

### Rate Limiting (REQ-GW-006)
- 대기열 조회: 60회/분 per user
- 예매/결제: 20회/분 per user
- 로그인: 10회/분 per user
- 기타: 200회/분 per user

### Timeout (REQ-GW-008, REQ-GW-009)
- Payment 라우트: 60초
- Queue 라우트: 10초
- 기타: 30초 (기본)

### Circuit Breaker (REQ-GW-007)
- 실패율 임계값 초과 시 Circuit Open
- Payment 서비스: 대기 시간 60초

## 보안 요구사항

### 인증/인가
- **JWT**: Access Token (1시간), Refresh Token (7일)
- **로그아웃**: Access Token 블랙리스트 (Redis) + Refresh Token 삭제
- **본인인증**: PortOne CI/DI 수집으로 1인 1계정 강제 (REQ-AUTH-004)
- **reCAPTCHA**: 회원가입 및 로그인 시 봇 차단 (REQ-AUTH-003)

### 암호화
- **비밀번호**: BCrypt 해싱 (REQ-AUTH-014)
- **개인정보**: AES-256 암호화 (이메일, 전화번호, CI/DI) - 선택 (REQ-AUTH-019)

## 개발 가이드라인

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
3. **멱등성**: 모든 Kafka Consumer는 멱등성 보장 로직 필수
4. **Outbox 패턴**: 이벤트 발행 시 Transactional Outbox 적용
5. **내부 API 보안**: `/internal/**` 엔드포인트는 API Key 검증 필수

### 확장성 고려사항
- **PostgreSQL**: 단일 인스턴스 → Read Replica → 서비스별 물리 분리 (마이그레이션 계획 존재)
- **Redis**: 단일 노드 → Multi-AZ Replication (트래픽 증가 시)
- **Kafka**: 단일 브로커 → 3 브로커 클러스터 (고가용성 전환)

## 로컬 개발 환경 (계획)

### 예상 Docker Compose 구성
```yaml
services:
  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_DB: ticketing
      # 스키마: user_service, event_service, reservation_service, payment_service, common

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
```

### 서비스 실행 순서 (미래)
1. 인프라 컨테이너 시작: `docker-compose up -d postgres redis kafka`
2. 스키마 및 테이블 생성 (Flyway/Liquibase 예정)
3. 각 서비스 Spring Boot 애플리케이션 시작

## 외부 서비스 연동

- **PortOne**: 본인인증(CI/DI), 결제 (테스트 모드)
- **reCAPTCHA**: Google reCAPTCHA v2/v3 (봇 차단)
- **OAuth2**: 카카오, 네이버, 구글 (소셜 로그인, 선택)

## 모니터링 및 로깅 (계획)

- **로그**: 구조화된 JSON 로그, CloudWatch Logs 전송
- **메트릭**: CloudWatch Metrics (라우트별 요청 수, 응답 시간, 에러율)
- **알람**: DLQ 메시지 10개 이상, Circuit Breaker Open 시 알림
- **분산 추적**: Request ID 전파 (REQ-GW-010)

## 현재 상태 및 다음 단계

**현재**: 설계 및 문서화 완료 (Draft 1.0.0)

**다음 단계**:
1. 각 서비스의 Spring Boot 프로젝트 생성 및 기본 구조 설정
2. PostgreSQL 스키마 및 ERD 기반 JPA Entity 구현
3. Redis 및 Kafka 연동 설정
4. API Gateway 라우팅 및 JWT 검증 필터 구현
5. Queue Service 대기열 로직 구현 (Redis Sorted Set, Lua 스크립트)
6. Reservation Service 분산 락 및 좌석 선점 로직 구현
7. Payment Service PortOne 연동 및 SAGA 패턴 구현
8. 통합 테스트 및 부하 테스트

---

**요약**: 이 프로젝트는 대규모 트래픽을 처리하는 티켓팅 시스템으로, MSA, 이벤트 기반 아키텍처, SAGA 패턴을 적용합니다. 모든 구현은 `docs/` 디렉토리의 요구사항 명세서와 아키텍처 설계서를 기준으로 진행하며, 서비스 간 데이터 격리, 멱등성 보장, 분산 락을 통한 동시성 제어가 핵심입니다.
