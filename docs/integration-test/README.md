# 통합 테스트 계획 — 프로젝트 완성 기준 (Source of Truth)

> K-pop 콘서트 티켓팅 MSA(Kotlin/Spring Boot + Redis 대기열 + Kafka 이벤트 + SAGA)의 **로컬 환경** 통합 테스트 체크리스트.
> 이 디렉터리의 **모든 체크박스가 통과(`- [x]`)되고 11.4 완성 조건을 충족하면 프로젝트를 "완성"으로 간주**한다.
> AWS 배포 관련 항목은 로컬 범위 밖이며 별도 배포 단계에서 검증한다(해당 TC는 "조건부/인프라"로 표기).

---

## 0. 이 문서 사용법 (세션 실행 프로토콜)

각 통합 테스트 문서(`00`~`11`)는 독립적으로 실행 가능한 테스트 케이스(TC)의 묶음이다.
**추후 각 세션이 한 문서(또는 한 영역)를 맡아 스스로 테스트를 수행하고 체크박스를 갱신**한다.

세션이 테스트를 시작할 때:

1. **`00_environment_setup.md`를 가장 먼저 완료**한다. 로컬 인프라(PostgreSQL/Valkey/Kafka/LocalStack)와 빌드/기동이 검증되지 않으면 다른 모든 테스트가 무의미하다.
2. 담당 문서를 열어 TC를 위에서부터 실행한다. 각 TC의 **사전조건 → 실행 단계 → 기대 결과 → 검증 포인트** 순서를 그대로 따른다.
3. 백엔드는 `curl` / `./gradlew test` / `./gradlew integrationTest`(TestContainers) / DB·Redis·Kafka 직접 조회로 검증한다.
4. **프론트엔드(`10_frontend_e2e.md`)는 세션이 Claude in Chrome MCP 도구로 직접 화면을 조작·관찰**한다(`navigate`, `read_page`, `computer`, `get_page_text`, `read_console_messages`, `read_network_requests`, `gif_creator`).
5. 결과를 아래 **상태 마킹 규칙**에 따라 기록하고, 실패 항목은 회귀 백로그로 남긴다.

---

## 1. 진행 대시보드

> 진행률은 각 문서의 `- [x]` 개수 / 전체 TC 개수로 계산한다. (초기 상태: 전부 미실행)

| # | 문서 | 영역 | TC 수 | P0(핵심) | 진행 | 주 검증 수단 |
|---|------|------|------:|------:|:----:|------|
| 00 | [00_environment_setup.md](./00_environment_setup.md) | 로컬 인프라 부트스트랩 & 관측 | 16 | 9 | `16 / 16` | docker-compose, gradle, curl health |
| 01 | [01_api_gateway.md](./01_api_gateway.md) | API Gateway (라우팅/인증/보안) | 22 | 9 | `22 / 22` | curl, gradle test (WebFlux) |
| 02 | [02_user_service.md](./02_user_service.md) | 인증/회원/JWT | 27 | 15 | `27 / 27` | curl, gradle test, Redis/DB |
| 03 | [03_event_service.md](./03_event_service.md) | 공연/좌석/캐싱/Consumer | 24 | 12 | `21 / 24` | curl, integrationTest, Redis/Kafka |
| 04 | [04_queue_service.md](./04_queue_service.md) | Redis 대기열/토큰 | 20 | 11 | `20 / 20` | curl, integrationTest, Redis/Lua |
| 05 | [05_reservation_service.md](./05_reservation_service.md) | 분산락/선점/Outbox | 22 | 12 | `20 / 22` | curl, integrationTest, Redisson/Outbox |
| 06 | [06_payment_service.md](./06_payment_service.md) | PortOne/SAGA/보상 트랜잭션 | 26 | 21 | `23 / 26` | curl, integrationTest, SAGA 상태 |
| 07 | [07_common_kafka_outbox.md](./07_common_kafka_outbox.md) | Kafka/Outbox/멱등성/DLQ | 18 | 10 | `18 / 18` | integrationTest (TestContainers) |
| 08 | [08_common_web_security.md](./08_common_web_security.md) | 내부 API/암호화/예외/Feign | 18 | 9 | `18 / 18` | gradle test, curl |
| 09 | [09_cross_service_flows.md](./09_cross_service_flows.md) | 크로스 서비스 E2E (SAGA/이벤트/정합성) | 20 | 8 | `18 / 20` | 다중 서비스 기동 + curl 시나리오 |
| 10 | [10_frontend_e2e.md](./10_frontend_e2e.md) | 프론트엔드 화면 E2E | 26 | 13 | `25 / 26` | **Claude in Chrome** |
| 11 | [11_requirement_coverage.md](./11_requirement_coverage.md) | REQ 커버리지 감사 & 보강(GAP) | 7 | 3 | `6 / 7` | 위 갭 보강 TC 실행 |
| | **합계** | | **245** | **132** | `233 / 245` | |

---

## 2. 권장 실행 순서 (의존성 기반)

```
00 환경 부트스트랩  ──▶  단위 서비스 검증 (01~08, 병렬 가능)  ──▶  09 크로스 서비스 E2E  ──▶  10 프론트엔드 E2E
                                                              └──▶  11 커버리지 갭 보강(GAP TC)
```

- **00**은 전제 조건이므로 단독 선행.
- **01~08**은 서비스별로 독립적이라 여러 세션이 병렬 진행 가능. 단, `09`/`10`은 관련 백엔드가 기동·검증된 뒤 수행해야 의미가 있다.
- **09 크로스 서비스 플로우**는 회원가입→대기열→선점→예매→결제→SAGA 확정→좌석 SOLD 반영까지 전 서비스를 동시에 띄워야 한다.
- **10 프론트엔드**는 백엔드 API가 정상 동작하는 상태에서 Claude in Chrome으로 실제 화면을 검증한다.

---

## 3. 상태 마킹 규칙

각 TC의 첫 줄 체크박스를 다음과 같이 갱신한다.

| 표기 | 의미 |
|------|------|
| `- [ ] 미실행` | 아직 실행하지 않음 (초기 상태) |
| `- [x] 통과 (YYYY-MM-DD, 근거: ...)` | 기대 결과·검증 포인트를 모두 만족 |
| `- [ ] 실패 (사유: ..., 이슈: #NN)` | 기대 결과 불충족 — 체크박스는 비운 채 사유/이슈 링크 기록 |
| `- [x] NA (사유: 로컬 범위 외 / 인프라 점검으로 대체)` | 조건부·인프라 항목으로 로컬 검증 불가 시, 대체 증빙 명기 후 처리 |

- **실패 항목은 회귀 백로그**로 관리하고, 수정 후 재실행하여 `- [x]`로 전환한다.
- 한 TC가 다른 TC의 산출물(예: `paymentId`)을 사전조건으로 요구하면, 선행 TC를 먼저 통과시킨다.

---

## 4. 검증 수단 빠른 참조

| 수단 | 용도 | 예시 |
|------|------|------|
| `cd docker && docker-compose up -d` | 로컬 인프라 기동 | PostgreSQL 5432 / Valkey 6379 / Kafka 9092 / LocalStack 4566 |
| `./gradlew test` | 단위 테스트 | 서비스별 비즈니스 로직 |
| `./gradlew integrationTest` | TestContainers 통합 테스트 | Outbox/Consumer/Repository 등 |
| `./gradlew :<service>:bootRun` | 개별 서비스 기동 | `:payment-service:bootRun` |
| `curl` (Gateway 경유) | API 레벨 검증 | `http://localhost:8080/...` (JWT/Queue Token 헤더) |
| `docker exec ticket-postgres psql ...` | DB 상태 검증 | outbox_events / processed_events / 좌석 상태 |
| `redis-cli` (`MONITOR`/`SMEMBERS`/`ZCARD`) | Redis 상태·명령 검증 | `hold_seats:{scheduleId}`, KEYS 미사용 확인 |
| Kafka consumer/`kafka-console-consumer` | 토픽·DLQ 메시지 검증 | 이벤트 발행/멱등성/DLQ |
| **Claude in Chrome MCP** | 프론트엔드 화면 E2E | `navigate`/`computer`/`read_network_requests`/`gif_creator` |

> 환경 변수(Profile `local`)는 루트 `CLAUDE.md`의 "빌드 및 실행" 섹션 참조.

---

## 5. 요구사항 커버리지 요약

상세는 [11_requirement_coverage.md](./11_requirement_coverage.md) 참조.

- 전체 요구사항: **135건** (REQ-xxx-xxx), 그중 **필수 99건**.
- 통합 테스트 `관련 REQ` 라벨 기준 **직접 커버리지 ≈ 84.8% (84/99)**. 암묵 라벨 보정·11.3 GAP 보강 TC 반영 시 로컬 검증 가능 필수 **97/99** 커버.
- 로컬 검증 불가(조건부/인프라): `REQ-INT-007`(VPC 네트워크), `REQ-FE-025`(Vercel 배포) → 별도 인프라/배포 단계 증빙.
- 잔여 백엔드 보강 GAP: `TC-GAP-001`(GW 타임아웃), `TC-GAP-002`(결제 타임아웃), `TC-GAP-003`(내부 API 감사 로깅), `TC-GAP-005`(좌석 등급/자동생성 정합성).

---

## 6. 프로젝트 완성 판정 (요약)

[11.4 완성 판정 기준](./11_requirement_coverage.md)의 세 조건을 **모두** 충족해야 한다.

1. **모든 P0(핵심) TC 통과** — 132개 P0 항목 전부 `- [x]`.
2. **필수 REQ 100% 커버** — 99개 필수 요구사항이 통과 TC로 커버(조건부 인프라 항목은 대체 증빙).
3. **모든 체크박스 `- [x]`** — 245개 TC + GAP 보강 전부 통과/NA 처리.

> 세 조건 중 하나라도 미충족이면 "진행 중"으로 분류하고, 미통과 P0 및 미커버 필수 REQ를 회귀 백로그로 관리한다.

---

## 문서 목록

| 파일 | 설명 |
|------|------|
| [00_environment_setup.md](./00_environment_setup.md) | 로컬 인프라 기동, 시크릿 주입, 스키마 분리, 빌드/테스트, 관측(Prometheus/Grafana) |
| [01_api_gateway.md](./01_api_gateway.md) | JWT 검증, Queue Token 전달, Rate Limiting, Circuit Breaker, /internal 차단, 헤더 인젝션 방어 |
| [02_user_service.md](./02_user_service.md) | 회원가입 단계, reCAPTCHA, CI/DI 1인1계정, BCrypt, RTR, 블랙리스트, OAuth2 |
| [03_event_service.md](./03_event_service.md) | 공연/좌석 CRUD, Redis 캐싱, Cache Stampede(Lua), Consumer 멱등성, DLQ, 재고 정합성 |
| [04_queue_service.md](./04_queue_service.md) | Sorted Set 진입(ZADD NX), 배치 승인(Lua), 토큰 TTL, 용량/Rate Limit, KEYS 금지 |
| [05_reservation_service.md](./05_reservation_service.md) | Redisson 분산락, 좌석 선점 경쟁, hold 만료, Outbox 원자성, 멱등성 |
| [06_payment_service.md](./06_payment_service.md) | PortOne, SAGA Orchestration, 보상 트랜잭션, 결제권한 경계, 중복결제 방지, 마스킹 |
| [07_common_kafka_outbox.md](./07_common_kafka_outbox.md) | Outbox 폴링/재시도/DLQ, ExceptionClassifier, IdempotentConsumer, SKIP LOCKED race |
| [08_common_web_security.md](./08_common_web_security.md) | X-Service-Api-Key(상수시간 비교), AES-256-GCM, HMAC, GlobalExceptionHandler, Feign |
| [09_cross_service_flows.md](./09_cross_service_flows.md) | 해피패스 E2E, 결제 실패 보상 체인, 동시성 통합, Kafka 코레오그래피, 캐시 무효화 전파 |
| [10_frontend_e2e.md](./10_frontend_e2e.md) | Claude in Chrome 화면 E2E — 회원가입/로그인/대기열/좌석/결제/마이페이지/미들웨어 가드 |
| [11_requirement_coverage.md](./11_requirement_coverage.md) | REQ↔TC 매핑, 카테고리별 커버리지, 보강 GAP TC, 완성 판정 기준 |

---

## 실행 이슈 목록 (회귀 백로그)

| 이슈 | TC | 사유 |
|------|-----|------|
| #278 | TC-EVT-005 | 판매 시작 후 artist 수정 차단 미작동 |
| #279 | TC-EVT-008 | 캐시 TTL 만료 후 재적재 미발생 |
| #280 | TC-EVT-011 | SeatDto.GradeGroup Redis 역직렬화 오류 |
| #286 | TC-FE-002 | /events 목록 페이지 null split 런타임 에러 |
| #287 | TC-RSV-009, TC-RSV-020 | cancelReservation detached 엔티티 dirty checking 미동작 |
| #291 | TC-FLOW-019 | GET /reservations 엔드포인트 미구현 |
| #292 | TC-FLOW-009 | docker pause/unpause 후 Kafka 코디네이터 재조정 실패 |
