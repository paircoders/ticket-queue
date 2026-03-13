# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**Ticket Queue**는 K-pop 콘서트와 같은 대규모 트래픽을 처리하는 티켓팅 시스템입니다. Redis 기반 대기열, Kafka 이벤트 아키텍처, SAGA 패턴을 적용한 MSA 구조이며, 현재 **구현 진행 단계(Implementation Phase)**에 있습니다.

---

## 문서 가이드 (Source of Truth: `docs/`)

코드 작성 전 반드시 관련 문서를 먼저 확인하세요. 모든 아키텍처 결정은 `docs/`가 기준입니다.

### 필수 읽기 순서 (아키텍처)
1. **`docs/REQUIREMENTS.md`** — 110개 요구사항 (기능 74 / 비기능 36), REQ-xxx-xxx ID 참조
2. **`docs/architecture/02_services.md`** — 6개 서비스 경계, 책임, API 엔드포인트 전체
3. **`docs/architecture/04_data.md`** — ERD, Redis 데이터 모델, 분산 락 전략
4. **`docs/architecture/05_kafka.md`** — Kafka 토픽/이벤트 스키마/Consumer Group/멱등성
5. **`docs/architecture/06_api_security.md`** — API Gateway 라우팅, JWT, Rate Limiting, 보안
6. **`docs/architecture/07_operations.md`** — 모니터링, 성능 목표, 테스트 전략

### API 명세서 (`docs/specification/`)
- `00_overview.md` — 공통 규약 (날짜 형식, 에러 응답, 인증 헤더)
- `01_user_service.md` ~ `05_payment_service.md` — 서비스별 상세 API

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Kotlin 2.1.0 (Java 21) |
| Framework | Spring Boot 3.5.10, Spring Cloud Gateway (2025.0.1) |
| Build | Gradle Kotlin DSL + KSP |
| DB | PostgreSQL 18 (단일 인스턴스, 스키마 분리) |
| ORM | Spring Data JPA + QueryDSL 6.12 |
| Cache/Queue | Valkey 8.1.5 (Redis 호환) |
| Messaging | Apache Kafka (KRaft 모드) |
| Dist. Lock | Redisson 3.40.2 |
| Resilience | Resilience4j 2.2.0 |
| JWT | JJWT 0.12.6 |
| Cloud | Spring Cloud AWS 3.4.2 (Secrets Manager) |
| Frontend | Next.js 16+ (미구현) |
| Infra | Docker Compose (로컬), AWS EC2 (목표) |

---

## 마이크로서비스 요약

| 서비스 | 담당자 | 핵심 책임 |
|--------|--------|----------|
| API Gateway | A | 라우팅, JWT 검증, Rate Limiting, Circuit Breaker |
| User Service | B | 인증(JWT), 회원관리, OAuth2, reCAPTCHA |
| Event Service | A | 공연/공연장/좌석 관리, Redis 캐싱 |
| Queue Service | A | Redis Sorted Set 대기열, Queue Token 발급 |
| Reservation Service | B | 좌석 선점(Redisson 분산 락), 예매 관리 |
| Payment Service | B | PortOne 연동, SAGA 패턴, 보상 트랜잭션 |

> 상세 책임/스키마/Kafka 역할: `docs/architecture/02_services.md`

---

## 아키텍처 핵심 패턴 (포인터)

| 패턴 | 한줄 설명 | 상세 문서 |
|------|----------|----------|
| **대기열 시스템** | Redis Sorted Set + Lua 원자적 배치 승인, Queue Token TTL 10분 | `04_data.md`, `03_queue_service.md` |
| **좌석 선점** | Redisson 분산 락 `seat:hold:{scheduleId}:{seatId}`, 최대 4장 | `04_data.md`, `04_reservation_service.md` |
| **SAGA 패턴** | Payment Service Orchestration, PaymentSuccess/Failed 이벤트 트리거 | `05_kafka.md`, `05_payment_service.md` |
| **Transactional Outbox** | Reservation·Payment Service 필수, `common.outbox_events`, 1초 폴링 | `05_kafka.md` |
| **Consumer 멱등성** | `common.processed_events` INSERT 선행, DataIntegrityViolationException 중복 감지 | `05_kafka.md` |
| **내부 API 보안** | `/internal/**` 경로 + `X-Service-Api-Key` 헤더, Gateway에서 차단 | `06_api_security.md` |

> Redis 키 패턴 전체: `04_data.md` §Redis 데이터 모델
> Kafka 토픽/이벤트 스키마/Consumer Group: `05_kafka.md`
> API 엔드포인트 전체 목록: `specification/` 각 파일

---

## 주의사항 및 설계 원칙 체크리스트

코드 작성 전 반드시 확인:

1. **문서 우선**: 해당 REQ-xxx-xxx ID를 `docs/REQUIREMENTS.md`에서 먼저 확인
2. **스키마 격리**: 서비스 간 직접 DB 쿼리 금지 — REST API 또는 Kafka 이벤트만 허용
3. **멱등성**: 모든 Kafka Consumer는 `common.processed_events` 테이블 INSERT 선행 필수
4. **Outbox 패턴**: Reservation·Payment Service에서 이벤트 발행 시 반드시 적용
5. **내부 API 보안**: `/internal/**` 엔드포인트는 `X-Service-Api-Key` 검증 필수
6. **Redis KEYS 명령 금지**: `hold_seats:{scheduleId}` SET 등 O(1) 자료구조로 대체
7. **결제 권한은 Reservation 기반**: Queue Token 만료 후에도 `hold_expires_at` 미경과 시 결제 가능
8. **DLQ 전략**: 재시도 가능(지수 백오프 3회) vs 즉시 DLQ(ValidationException 등) 구분

---

## 핵심 설계 결정 (ADR 요약)

1. **단일 PostgreSQL 인스턴스**: 비용 효율, 향후 물리 분리 가능
2. **Redis KEYS 명령 금지**: `hold_seats:{scheduleId}` SET으로 대체
3. **Outbox Pattern 필수**: Reservation, Payment Service (P0 정합성)
4. **Consumer 멱등성**: `common.processed_events` + Unique Constraint (원자적 중복 방지)
5. **단일 Queue Token + Reservation 기반 결제 권한**: 결제는 `Reservation(PENDING + hold_expires_at)` 검증
6. **DLQ 재시도 전략**: 지수 백오프 3회, 재시도 불가 예외는 즉시 DLQ
7. **보상 토픽 제외**: `payment.events`의 PaymentFailed가 보상 트리거 (YAGNI)
8. **개인정보 AES 암호화 + 해시**: 이메일/이름/전화번호/CI 암호화, 검색용 해시(emailHash 등) 별도

---

## 현재 상태 및 다음 단계

**현재**: 구현 진행 중 (Implementation Phase)

### ✅ 완료된 구현
- **Common 모듈** — Outbox Pattern, Kafka 멱등성, 공통 이벤트 스키마, 내부 API 보안, 공통 설정
- **Event Service** — Venue/Hall CRUD API, JPA Entity, Kafka·Redis 설정, 내부 API 보안 필터
- **User Service** — 회원가입 API (reCAPTCHA, AES 암호화, 해시 중복 체크)
- **API Gateway** — TraceId 전파 필터, 기본 구조
- **Payment/Queue/Reservation Service** — Kafka·Redis·Redisson 기본 설정

### 🔄 진행 필요
1. **User Service**: 로그인/로그아웃/토큰 갱신, JWT 발급, Refresh Token Rotation
2. **API Gateway**: JWT 검증 필터, 라우팅 설정, Circuit Breaker
3. **Event Service**: 공연/회차/좌석 CRUD API, Redis 캐싱
4. **Queue Service**: 대기열 진입/상태/이탈 API, Queue Token 발급
5. **Reservation Service**: 좌석 선점 API, 예매 조회/관리, Kafka Consumer
6. **Payment Service**: PortOne 연동, 결제 API, SAGA 패턴, Outbox Pattern
7. **통합 테스트 및 부하 테스트** (k6)

---

## 개발 환경 설정 및 실행

### 사전 요구사항
- JDK 21+, Docker & Docker Compose, Gradle (Wrapper 포함)

### 필수 환경 변수 (Active Profile: `local`)
```
SPRING_CLOUD_AWS_REGION_STATIC=ap-northeast-2
SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY=test
SPRING_CLOUD_AWS_CREDENTIALS_SECRET_KEY=test
SPRING_CLOUD_AWS_SECRETSMANAGER_ENDPOINT=http://192.168.50.111:4566
```

### 로컬 실행 순서
```bash
# 1. 인프라 (PostgreSQL 5432, Valkey 6379, Kafka 9092)
cd docker && docker-compose up -d

# 2. 빌드
cd backend && ./gradlew build

# 3. 서비스 실행
./gradlew :api-gateway:bootRun
./gradlew :user-service:bootRun
./gradlew :event-service:bootRun
./gradlew :queue-service:bootRun
./gradlew :reservation-service:bootRun
./gradlew :payment-service:bootRun

# 4. 테스트
./gradlew test
./gradlew integrationTest   # TestContainers
```

---

## 프로젝트 구조
```
ticket-queue/
├── backend/
│   ├── common/                    # 공통 모듈 (Outbox, Processed Events 등)
│   ├── api-gateway/
│   ├── user-service/
│   ├── event-service/
│   ├── queue-service/
│   ├── reservation-service/
│   └── payment-service/
├── docker/
│   ├── docker-compose.yml
│   └── db_init/                   # PostgreSQL 초기화 스크립트
├── docs/
│   ├── REQUIREMENTS.md
│   ├── architecture/              # 상세 아키텍처 문서
│   └── specification/             # 서비스별 API 명세
└── frontend/                      # Next.js (미구현)
```

---

## 개발 가이드라인

- **코드 스타일**: Kotlin 코딩 컨벤션, IntelliJ IDEA 기본 포맷터
- **Commit**: Conventional Commits (`feat:`, `fix:`, `refactor:`, `chore:`, `docs:`)
- **테스트**: JUnit 5 + MockK + Kotest (Mockito 대신 MockK), TestContainers
- **브랜치**: `main` (프로덕션) / `develop` (통합) / `feature/*` / `hotfix/*`

---

## 자주 사용하는 명령어

```bash
# 인프라 재시작 (초기화)
cd docker && docker-compose down -v && docker-compose up -d

# 빠른 빌드 (테스트 제외)
./gradlew build -x test

# 특정 서비스만
./gradlew :user-service:clean :user-service:build :user-service:bootRun
./gradlew :reservation-service:test

# PostgreSQL 접속
docker exec -it ticket-postgres psql -U ticketing_admin -d ticketing

# Valkey CLI
docker exec -it ticket-valkey valkey-cli

# Kafka 디버깅
docker exec -it ticket-kafka bash
kafka-topics.sh --bootstrap-server localhost:9092 --list
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payment.events --from-beginning
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group reservation-payment-consumer
```
