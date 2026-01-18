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
  - Key: `queue:{scheduleId}` (공연별이 아닌 회차별 대기열)
- **배치 승인**: 1초마다 10명씩 Lua 스크립트로 원자적 처리 (36,000명/시간)
  - Lua 스크립트로 ZRANGE + ZREM + Token 발급을 원자적으로 처리
- **이중 Token 모델**:
  - `qr_xxx`: Reservation Token (좌석 조회/선점용, TTL 10분)
  - `qp_xxx`: Payment Token (결제용, TTL 10분)
  - Token 데이터: Redis String `queue:token:{token}` (JSON: userId, scheduleId, type, issuedAt)
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
| `queue:token:{token}` | String (JSON) | Queue Token (qr_xxx, qp_xxx) | 10분 | Queue |
| `queue:active:{userId}` | String | 중복 대기 방지 (scheduleId 저장) | 10분 | Queue |
| `seat:hold:{scheduleId}:{seatId}` | String | 좌석 선점 락 (Redisson) | 5분 | Reservation |
| `hold_seats:{scheduleId}` | Set | HOLD 좌석 ID 목록 (KEYS 대체) | 없음 | Reservation |
| `token:blacklist:{token}` | String | Access Token 블랙리스트 | 1시간 | User |
| `cache:event:list:{page}:{size}:{filters}` | String (JSON) | 공연 목록 캐시 | 5분 | Event |
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
   - Reservation Token 만료: 401 에러 + 대기열 페이지 리디렉션
   - Payment Token 만료 (결제 중): PortOne Callback에서는 Token 검증 제외 (merchantUid + 금액 검증으로 대체)
   - Frontend Timer 표시로 사용자에게 남은 시간 시각화 권장
8. **DLQ 처리 전략**:
   - 재시도 가능한 예외: 지수 백오프로 최대 3회 재시도 (TimeoutException, 네트워크 오류 등)
   - 재시도 불가능한 예외: 즉시 DLQ 이동 (ValidationException, DataIntegrityViolationException 등)
   - DLQ 모니터링: 메시지 10개 이상 시 알람

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
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: admin_password
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./scripts/init-schemas.sql:/docker-entrypoint-initdb.d/01-init-schemas.sql
    # 스키마: user_service, event_service, reservation_service, payment_service, common

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1

volumes:
  postgres_data:
  redis_data:
```

### 개발 환경 구축 (예정)

**1. 인프라 시작:**
```bash
# Docker Compose로 인프라 시작
docker-compose up -d

# 컨테이너 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f postgres redis kafka
```

**2. Kafka 토픽 생성:**
```bash
# reservation.events 토픽 생성
docker exec -it kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic reservation.events \
  --partitions 3 \
  --replication-factor 1

# payment.events 토픽 생성
docker exec -it kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic payment.events \
  --partitions 3 \
  --replication-factor 1

# DLQ 토픽 생성
docker exec -it kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic dlq.reservation \
  --partitions 1 \
  --replication-factor 1

docker exec -it kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic dlq.payment \
  --partitions 1 \
  --replication-factor 1

# 토픽 목록 확인
docker exec -it kafka kafka-topics --list --bootstrap-server localhost:9092
```

**3. PostgreSQL 스키마 및 DB 사용자 생성:**
```bash
# PostgreSQL 컨테이너 접속
docker exec -it postgres psql -U admin -d ticketing

# SQL 스크립트 실행 (예정)
\i /docker-entrypoint-initdb.d/01-init-schemas.sql
```

**4. Spring Boot 서비스 실행 (예정):**
```bash
# 각 서비스 디렉토리에서 실행
cd services/api-gateway
./gradlew bootRun  # 또는 mvn spring-boot:run

cd services/user-service
./gradlew bootRun

cd services/event-service
./gradlew bootRun

cd services/queue-service
./gradlew bootRun

cd services/reservation-service
./gradlew bootRun

cd services/payment-service
./gradlew bootRun
```

**5. 인프라 중지:**
```bash
# 컨테이너 중지 (데이터 유지)
docker-compose stop

# 컨테이너 중지 및 삭제 (데이터 유지)
docker-compose down

# 컨테이너 및 볼륨 모두 삭제 (데이터 초기화)
docker-compose down -v
```

### 유틸리티 명령어 (예정)

**Redis 모니터링:**
```bash
# Redis CLI 접속
docker exec -it redis redis-cli

# 대기열 확인
ZRANGE queue:schedule-001 0 -1 WITHSCORES

# Token 확인
GET queue:token:qr_abc123

# HOLD 좌석 확인
SMEMBERS hold_seats:schedule-001

# 캐시 확인
HGETALL cache:event:event-123
```

**Kafka 모니터링:**
```bash
# Consumer Group 확인
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list

# Consumer Group 상세 정보
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group reservation-payment-consumer \
  --describe

# 특정 토픽 메시지 읽기
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic payment.events \
  --from-beginning \
  --max-messages 10
```

**PostgreSQL 관리:**
```bash
# 테이블 목록 확인
\dt user_service.*
\dt event_service.*
\dt reservation_service.*
\dt payment_service.*
\dt common.*

# 특정 스키마의 모든 테이블 크기 확인
SELECT schemaname, tablename,
       pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'event_service'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

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

**현재**: 설계 및 문서화 완료 (Draft 1.0.0)
- 121개 요구사항 정의 완료 (필수 94개, 선택 27개)
- 최근 업데이트:
  - 데이터 아키텍처: User/Event 스키마 상세 설계 완료
  - 메시징 아키텍처: DLQ 예외 분류 및 재시도 전략 구체화
  - 도메인 로직: Queue Token 만료 UX 플로우 상세 설계

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
5. **Queue Token 만료 UX**: PortOne Callback은 Token 검증 제외 (merchantUid + 금액 검증)
6. **DLQ 재시도 전략**: 지수 백오프 3회, 재시도 불가능 예외는 즉시 DLQ
7. **보상 토픽 제외**: `payment.events`의 PaymentFailed가 보상 트리거 (YAGNI 원칙)
8. **개인정보 평문 저장**: 암호화 복잡도 제거, 아키텍처/로직 검증 집중 (포트폴리오 최적화)

---

**요약**: 이 프로젝트는 대규모 트래픽을 처리하는 티켓팅 시스템으로, MSA, 이벤트 기반 아키텍처, SAGA 패턴을 적용합니다. 모든 구현은 `docs/` 디렉토리의 요구사항 명세서와 아키텍처 설계서를 기준으로 진행하며, 서비스 간 데이터 격리, 멱등성 보장, 분산 락을 통한 동시성 제어가 핵심입니다.
