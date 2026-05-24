# CLAUDE.md

K-pop 콘서트 티켓팅 시스템. Redis 대기열 + Kafka 이벤트 + SAGA 패턴을 적용한 Kotlin/Spring Boot MSA.

---

## 작업 원칙 (위반 시 코드 품질·정합성에 직접 영향)

코드 작성 전 반드시 확인:

1. **문서 우선**: 관련 `docs/` 문서를 먼저 읽고, 해당 `REQ-xxx-xxx` ID를 `docs/REQUIREMENTS.md`에서 확인
2. **스키마 격리**: 서비스 간 직접 DB 쿼리 금지 — REST API 또는 Kafka 이벤트만 허용
3. **Consumer 멱등성**: 모든 Kafka Consumer는 `common.processed_events` INSERT 선행 필수, `DataIntegrityViolationException`으로 중복 감지
4. **Outbox 패턴**: Reservation·Payment Service는 `common.outbox_events`로만 이벤트 발행 (1초 폴링)
5. **내부 API 보안**: `/internal/**` 엔드포인트는 `X-Service-Api-Key` 검증 필수, Gateway에서 외부 차단
6. **Redis KEYS 명령 금지**: `hold_seats:{scheduleId}` SET 등 O(1) 자료구조로 대체
7. **결제 권한**: Queue Token 만료 후에도 `Reservation(PENDING + hold_expires_at 미경과)` 검증으로 결제 가능
8. **DLQ 전략**: 재시도 가능(지수 백오프 3회) vs 즉시 DLQ(`ValidationException` 등) 구분

---

## 문서 가이드 (Source of Truth)

| 문서 | 내용 |
|------|------|
| `docs/REQUIREMENTS.md` | 110개 요구사항 (REQ-xxx-xxx ID) |
| `docs/architecture/02_services.md` | 6개 서비스 경계, 책임, 엔드포인트 |
| `docs/architecture/04_data.md` | ERD, Redis 키 패턴, 분산 락 |
| `docs/architecture/05_kafka.md` | 토픽, 이벤트 스키마, Consumer Group, 멱등성 |
| `docs/architecture/06_api_security.md` | Gateway 라우팅, JWT, Rate Limiting |
| `docs/architecture/07_operations.md` | 모니터링, 성능 목표, 테스트 전략 |
| `docs/specification/*.md` | 서비스별 API 명세 (`00_overview.md` = 공통 규약) |

---

## 기술 스택

- **언어/런타임**: Kotlin 2.1.0 (Java 21)
- **프레임워크**: Spring Boot 3.5.10, Spring Cloud Gateway 2025.0.1
- **빌드**: Gradle Kotlin DSL + KSP
- **저장소**: PostgreSQL 18 (단일 인스턴스, 스키마 분리), Valkey 8.1.5 (Redis 호환), Kafka (KRaft)
- **ORM/라이브러리**: Spring Data JPA + QueryDSL 6.12, Redisson 3.40.2, Resilience4j 2.2.0, JJWT 0.12.6
- **AWS**: Spring Cloud AWS 3.4.2 (Secrets Manager)
- **테스트**: JUnit 5 + MockK + Kotest, TestContainers (Mockito 금지)
- **커밋**: Conventional Commits (`feat:`, `fix:`, `refactor:`, `chore:`, `docs:`)
- **브랜치**: `main` / `develop` / `feature/*` / `hotfix/*`

---

## 서비스 구성

| 서비스 | 핵심 책임 |
|--------|----------|
| `api-gateway` | 라우팅, JWT 검증, Rate Limiting, Circuit Breaker |
| `user-service` | 인증(JWT), 회원관리, OAuth2, reCAPTCHA |
| `event-service` | 공연/공연장/좌석 관리, Redis 캐싱 |
| `queue-service` | Redis Sorted Set 대기열, Queue Token 발급 |
| `reservation-service` | 좌석 선점(Redisson 분산 락), 예매, Outbox |
| `payment-service` | PortOne 연동, SAGA Orchestration, 보상 트랜잭션 |

---

## 빌드 및 실행

### 환경 변수 (Profile: `local`)
```
SPRING_CLOUD_AWS_REGION_STATIC=ap-northeast-2
SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY=test
SPRING_CLOUD_AWS_CREDENTIALS_SECRET_KEY=test
SPRING_CLOUD_AWS_SECRETSMANAGER_ENDPOINT=http://localhost:4566
INTERNAL_API_KEY=local-dev-internal-api-key
```

### 명령어
```bash
# 인프라 (PostgreSQL 5432, Valkey 6379, Kafka 9092)
cd docker && docker-compose up -d

# 빌드 / 테스트
cd backend && ./gradlew build
./gradlew test
./gradlew integrationTest   # TestContainers

# 서비스 실행
./gradlew :<service>:bootRun
```
