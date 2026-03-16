# Issue #199: [Test] 보안/정확성 핵심 클래스 테스트 커버리지 강화

## Overview

리뷰에서 식별된 P0/P1 클래스들의 테스트 부재 및 품질 저하 문제를 해결합니다. 보안 취약점 수정(헤더 인젝션 #192, timing attack #195)과 버그 수정(DLQ replay #196, Queue Token prefix #193)이 실제로 방어되는지 검증하는 테스트를 우선 추가합니다. 추가로 테스트 인프라(`@MockitoBean` → `@MockkBean`, `Thread.sleep` → `Awaitility`)를 프로젝트 표준에 맞게 정비합니다.

## Branch
`chore/199-test-security-core-classes`

## Checklist

### P0 — 테스트 없음 (즉시 추가)
- [x] `common-jpa/src/test` 디렉토리 및 기본 테스트 구조 생성
- [x] `IdempotentConsumerTemplate` 단위 테스트 (DLQ replay 차단, 멱등성 보장) — 4개 케이스
- [x] `ExceptionClassifier` 단위 테스트 (retryable/non-retryable 분류 정확성) — 13개 케이스
- [x] `GatewayAuthFilter` 단위/통합 테스트 (X-User-Id/X-User-Role 헤더 인젝션 방어 #192) — 7개 케이스
- [x] `InternalApiKeyValidator` 단위 테스트 (timing attack 방어, 기본값 키 거부 검증 #195) — 기존 존재 확인
- [x] `OutboxEventRepositoryCustomImpl` 슬라이스 테스트 (TestContainers, Outbox 폴링 쿼리 정확성) — 3개 케이스

### P1 — 테스트 보강
- [x] `queue-enter.lua` 테스트 — 동시 진입, 정원 초과, 중복 진입 엣지 케이스 — 5개 케이스
- [x] `batch-approve.lua` 테스트 — `qr_` prefix 생성 검증 (#193 연계) — 5개 케이스
- [ ] `POST /queue/enter` 컨트롤러 테스트 — 유효성 검사 실패, 인증 실패 케이스
- [x] `GlobalExceptionHandler` 테스트 — 각 예외 타입별 HTTP 응답 코드 검증 — 10개 케이스
- [x] `JwtTokenProvider` 테스트 — 만료 토큰, 잘못된 알고리즘, 블랙리스트 토큰 — 6개 케이스

### 테스트 인프라 정비
- [x] `Thread.sleep` 사용처 전체 검색 — 사용처 없음 확인
- [x] `@MockitoBean` 사용처 전체 검색 — 사용처 없음 확인

## Implementation Plan

### Files likely to change

```
backend/
├── common-jpa/
│   └── src/test/kotlin/com/ticketqueue/common/
│       ├── outbox/OutboxEventRepositoryCustomImplTest.kt  (신규)
│       └── idempotent/IdempotentConsumerTemplateTest.kt   (신규)
├── common/
│   └── src/test/kotlin/com/ticketqueue/common/
│       └── exception/ExceptionClassifierTest.kt           (신규)
├── api-gateway/
│   └── src/test/kotlin/com/ticketqueue/gateway/
│       ├── filter/GatewayAuthFilterTest.kt                (신규)
│       └── security/InternalApiKeyValidatorTest.kt        (신규)
├── queue-service/
│   └── src/test/
│       ├── kotlin/com/ticketqueue/queue/
│       │   └── controller/QueueControllerTest.kt          (보강)
│       └── resources/lua/
│           ├── QueueEnterLuaTest.kt                       (신규)
│           └── BatchApproveLuaTest.kt                     (신규)
└── **/  (Thread.sleep → Awaitility, @MockitoBean → @MockkBean 교체)
```

### Approach

1. **`common-jpa` 테스트 구조 생성**: `build.gradle.kts`에 testImplementation 의존성 추가 (JUnit 5, MockK, TestContainers PostgreSQL)

2. **보안 관련 테스트 우선** (GatewayAuthFilter, InternalApiKeyValidator):
   - `GatewayAuthFilter`: X-User-Id/X-User-Role 헤더가 외부 요청에서 제거되는지, JWT 검증 후 올바르게 주입되는지 검증
   - `InternalApiKeyValidator`: `MessageDigest.isEqual()` 기반 constant-time 비교 검증, 빈 키/기본값 키 거부

3. **멱등성/Outbox 테스트**:
   - `IdempotentConsumerTemplate`: `DataIntegrityViolationException` 발생 시 DLQ replay 차단 확인, 정상 메시지 처리 확인
   - `OutboxEventRepositoryCustomImpl`: TestContainers로 실제 PostgreSQL 연동, 미전송 이벤트 조회 쿼리 검증

4. **Lua 스크립트 테스트**: Embedded Redis (it.ozimov:embedded-redis 또는 TestContainers Valkey)로 `queue-enter.lua`, `batch-approve.lua` 실행, 엣지 케이스 검증

5. **인프라 정비**: `grep -r "Thread.sleep"`, `grep -r "@MockitoBean"` 결과 기반으로 일괄 교체

### 테스트 기술 스택
- 단위 테스트: JUnit 5 + MockK + Kotest assertions
- 슬라이스 테스트: `@DataJpaTest` + TestContainers (PostgreSQL)
- Lua 테스트: TestContainers Valkey 또는 `EmbeddedRedisServer`
- 타이밍 테스트: Awaitility (Thread.sleep 대체)

## References
- GitHub Issue: #199
- 연관 PR: #192 (GatewayAuthFilter), #195 (InternalApiKeyValidator), #196 (IdempotentConsumerTemplate), #193 (Queue Token prefix)
- 문서: `docs/architecture/07_operations.md` — 테스트 전략
- Branch: `chore/199-test-security-core-classes`
- Worktree path: `/Users/taekwon/work/project/ticket-queue-199`
- Created: 2026-03-16
