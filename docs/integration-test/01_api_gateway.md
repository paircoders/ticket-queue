## 01. API Gateway (라우팅/인증/보안)

> **영역 범위**: Spring Cloud Gateway WebFlux 필터 체인 전체 — JWT 인증, Queue Token 형식 검증, 블랙리스트, 내부 경로 차단, CORS, 보안 헤더, Rate Limiting, Circuit Breaker, TraceId 전파, 요청 크기 제한
> **사전 준비**: `cd docker && docker-compose up -d` (Valkey/Redis 6379 필수), api-gateway `local` 프로파일로 기동 (`./gradlew :api-gateway:bootRun`), 유효한 HS512 JWT 발급 가능한 시크릿 공유 확인
> **주 실행 수단**: curl + gradle test (WebFlux), 실행 중인 게이트웨이 대상 (포트 8080)
> **총 항목 수**: 22

---

### TC-GW-001 — Authorization 헤더 완전 누락 시 보호 엔드포인트 401 UNAUTHORIZED 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-002, REQ-AUTH-006
- **분류**: 예외
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, Redis 연결 정상
- **실행 단계**:
  1. Authorization 헤더 없이 보호 엔드포인트 호출
     ```bash
     curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/users/me
     # 기대: 401
     curl -s http://localhost:8080/users/me | jq .
     # 기대: {"code":"UNAUTHORIZED","message":"인증이 필요합니다.","timestamp":"...","traceId":"..."}
     ```
  2. 응답 본문 JSON 필드 확인 (`code`, `message`, `timestamp`, `traceId` 모두 존재)
- **기대 결과**: HTTP 401, 응답 본문 `code` = `"UNAUTHORIZED"`, Content-Type = `application/json`
- **검증 포인트**: HTTP 상태 코드 401, `code` 필드값, 응답에 `X-Trace-Id` 헤더 포함 여부

---

### TC-GW-002 — Bearer 형식 아닌 Authorization 헤더 (Basic, 토큰만) 시 401 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-002
- **분류**: 예외, 경계값
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중
- **실행 단계**:
  1. `Basic` scheme 사용
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Basic dXNlcjpwYXNz"
     # 기대: 401
     ```
  2. `Bearer ` 접두사 없이 토큰만 전송
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: eyJhbGciOiJIUzUxMiJ9.fake"
     # 기대: 401
     ```
  3. 빈 Authorization 헤더
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: "
     # 기대: 401
     ```
- **기대 결과**: 모든 케이스에서 HTTP 401, `code` = `"UNAUTHORIZED"`
- **검증 포인트**: `INVALID_TOKEN`이 아닌 `UNAUTHORIZED` 코드 반환 (Bearer prefix 미인식 경로)

---

### TC-GW-003 — 서명 불일치 JWT (다른 시크릿으로 서명) 시 401 INVALID_TOKEN 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-002, REQ-AUTH-009
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, 잘못된 시크릿으로 서명한 HS512 토큰 준비
- **실행 단계**:
  1. 다른 Base64 인코딩 시크릿(64바이트)으로 HS512 JWT 생성
     ```bash
     # Python 예시로 위조 토큰 생성
     python3 -c "
     import jwt, base64, os
     fake_secret = base64.b64encode(os.urandom(64)).decode()
     token = jwt.encode({'sub':'1','role':'USER','jti':'fake-jti'}, base64.b64decode(fake_secret), algorithm='HS512')
     print(token)
     "
     ```
  2. 위조 토큰으로 요청
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Bearer <위조토큰>"
     # 기대: 401
     curl -s http://localhost:8080/users/me \
       -H "Authorization: Bearer <위조토큰>" | jq .code
     # 기대: "INVALID_TOKEN"
     ```
- **기대 결과**: HTTP 401, `code` = `"INVALID_TOKEN"`, `EXPIRED_TOKEN` 아님
- **검증 포인트**: 서명 검증 실패 시 `JwtException` 경로로 처리되어 `INVALID_TOKEN` 반환

---

### TC-GW-004 — 만료된 JWT 토큰으로 요청 시 401 EXPIRED_TOKEN 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-002, REQ-AUTH-010
- **분류**: 예외
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, 이미 만료된 토큰 생성 (expirationMs를 과거로 설정)
- **실행 단계**:
  1. 만료 시각을 과거(예: -3600초)로 설정한 HS512 JWT 생성
     ```bash
     # GatewayIntegrationTestSupport.createValidToken 참고
     # expirationMs = -3_600_000L (1시간 전 만료)
     # 테스트 코드로 생성하거나 jwt.io에서 exp를 과거로 설정
     ```
  2. 만료 토큰으로 보호 엔드포인트 호출
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Bearer <만료토큰>"
     # 기대: 401
     curl -s http://localhost:8080/users/me \
       -H "Authorization: Bearer <만료토큰>" | jq .code
     # 기대: "EXPIRED_TOKEN"
     ```
- **기대 결과**: HTTP 401, `code` = `"EXPIRED_TOKEN"` (서명 유효하나 만료된 토큰과 서명 불일치 토큰 구분)
- **검증 포인트**: `INVALID_TOKEN`이 아닌 `EXPIRED_TOKEN`으로 구분 반환, Micrometer `gateway.jwt.rejected.total{reason="expired"}` 카운터 증가

---

### TC-GW-005 — 필수 클레임(sub/role/jti) 누락 JWT 시 401 INVALID_TOKEN 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-002
- **분류**: 예외, 경계값
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중, 올바른 시크릿으로 서명하되 특정 클레임 누락한 토큰 3종 준비
- **실행 단계**:
  1. `sub` 클레임 누락 토큰 생성 후 요청
     ```bash
     curl -s http://localhost:8080/users/me \
       -H "Authorization: Bearer <sub없는토큰>" | jq .code
     # 기대: "INVALID_TOKEN"
     ```
  2. `role` 클레임 누락 토큰 생성 후 요청 — 동일 결과 확인
  3. `jti` 클레임 누락 토큰 생성 후 요청 — 동일 결과 확인
- **기대 결과**: 모든 케이스 HTTP 401, `code` = `"INVALID_TOKEN"`
- **검증 포인트**: `JwtTokenProvider.validateAndExtract` 내 `takeIf { it.isNotBlank() }` 분기가 `JwtException`을 던지는지 확인

---

### TC-GW-006 — HS256 알고리즘으로 서명된 토큰(Algorithm Confusion Attack) 시 401 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-002
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, HS256으로 서명한 구조상 유효한 JWT 준비
- **실행 단계**:
  1. 동일 시크릿으로 HS256 알고리즘 사용 JWT 생성
     ```bash
     python3 -c "
     import jwt, base64
     secret_b64 = '<게이트웨이_JWT_시크릿_BASE64>'
     secret = base64.b64decode(secret_b64)
     token = jwt.encode({'sub':'1','role':'USER','jti':'test-jti'}, secret, algorithm='HS256')
     print(token)
     "
     ```
  2. HS256 토큰으로 보호 엔드포인트 호출
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Bearer <hs256토큰>"
     # 기대: 401
     ```
  3. api-gateway 애플리케이션 로그에서 `Algorithm mismatch detected` 에러 로그 확인
- **기대 결과**: HTTP 401, `code` = `"INVALID_TOKEN"`, 로그에 `Algorithm mismatch detected: expected=HS512, actual=HS256` 기록
- **검증 포인트**: `alg` 헤더 불일치 감지 로그(ERROR 레벨), 401 응답

---

### TC-GW-007 — 블랙리스트 등록된 토큰(로그아웃 토큰)으로 요청 시 401 INVALID_TOKEN 반환

- [ ] 미실행
- **관련 REQ**: REQ-AUTH-011, REQ-GW-002
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, Redis 연결 정상, 유효한 JWT 생성 후 해당 `jti`를 Redis에 블랙리스트 등록
- **실행 단계**:
  1. 유효한 JWT 발급 (`jti` 값 추출)
  2. Redis에 블랙리스트 키 직접 삽입
     ```bash
     # JWT payload의 jti 추출 (base64 decode 두 번째 세그먼트)
     JTI="<추출한_jti_uuid>"
     redis-cli -h localhost -p 6379 -a <password> SET "token:blacklist:${JTI}" "1" EX 3600
     ```
  3. 해당 토큰으로 보호 엔드포인트 요청
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Bearer <해당토큰>"
     # 기대: 401
     curl -s http://localhost:8080/users/me \
       -H "Authorization: Bearer <해당토큰>" | jq .code
     # 기대: "INVALID_TOKEN"
     ```
- **기대 결과**: HTTP 401, `code` = `"INVALID_TOKEN"`, Redis `token:blacklist:{jti}` 키 조회 확인
- **검증 포인트**: `ReactiveTokenBlacklistService.isBlacklisted` 가 `true` 반환 → 블랙리스트 거부 로그 확인

---

### TC-GW-008 — Redis 블랙리스트 CircuitBreaker OPEN 상태에서 fail-open (로그인 정상 허용)

- [ ] 미실행
- **관련 REQ**: REQ-GW-002
- **분류**: 예외, 동시성
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중, `redisBlacklist` CircuitBreaker OPEN 상태 강제 유발 가능
- **실행 단계**:
  1. Redis를 의도적으로 응답 불가 상태로 전환 (예: `redis-cli DEBUG SLEEP 10`)
  2. 블랙리스트 확인 실패를 `minimumNumberOfCalls`(운영: 20회, 테스트: 3회) 이상 연속 발생시켜 CircuitBreaker OPEN 전환
  3. OPEN 상태에서 유효한 JWT로 보호 엔드포인트 요청
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Bearer <유효토큰>"
     # 기대: 401이 아닌 5xx (downstream 없음) 또는 정상 응답
     # 핵심: Redis 장애에도 블랙리스트 확인 실패로 인한 401이 아닌 정상 통과
     ```
  4. api-gateway 로그에서 `Redis blacklist circuit OPEN — fail-open` 경고 확인
  5. Micrometer 메트릭 확인
     ```bash
     curl http://localhost:9080/actuator/prometheus | grep gateway_blacklist_circuit
     # 기대: gateway_blacklist_circuit_open_passed_total 카운터 증가
     ```
- **기대 결과**: CircuitBreaker OPEN 상태에서도 JWT 유효 요청은 통과(fail-open), `gateway.blacklist.circuit.open.passed` 카운터 증가
- **검증 포인트**: 로그의 `fail-open` 경고, Prometheus 메트릭, 401 거부가 아닌 요청 통과 여부

---

### TC-GW-009 — X-User-Id/X-User-Role 헤더 인젝션 공격 차단 (Strip-First 패턴)

- [ ] 미실행
- **관련 REQ**: REQ-GW-002
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, 다운스트림 중 하나(예: user-service 8081)를 실행하여 수신 헤더 확인 가능
- **실행 단계**:
  1. `X-User-Id: 99999`, `X-User-Role: ADMIN` 헤더를 인젝션하여 JWT 없이 보호 경로 요청
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "X-User-Id: 99999" \
       -H "X-User-Role: ADMIN"
     # 기대: 401 (JWT 없음)
     ```
  2. 유효한 USER 역할 JWT와 함께 ADMIN 역할 헤더 인젝션 시도
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Bearer <USER_토큰>" \
       -H "X-User-Id: 99999" \
       -H "X-User-Role: ADMIN"
     # 기대: JWT 클레임 기반 X-User-Role=USER로 덮어씌워져 전달
     # user-service에서 수신한 X-User-Role 값이 "USER"인지 확인
     ```
  3. user-service 수신 헤더 로그에서 `X-User-Id`가 JWT `sub` 값, `X-User-Role`이 JWT `role` 값인지 검증
- **기대 결과**: 외부에서 인젝션한 신뢰 헤더는 제거되고, JWT 클레임 기반 헤더만 downstream으로 전달됨
- **검증 포인트**: downstream 수신 `X-User-Id` = JWT `sub`, `X-User-Role` = JWT `role` (인젝션 값 아님)

---

### TC-GW-010 — USER 역할로 관리자 전용 엔드포인트(POST /events) 접근 시 403 FORBIDDEN 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-015, REQ-GW-020
- **분류**: 보안
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중, `role=USER`로 서명된 유효 JWT 준비, Redis 블랙리스트 미등록
- **실행 단계**:
  1. `role=USER` JWT로 관리자 전용 엔드포인트 호출
     ```bash
     curl -s -w "\n%{http_code}" -X POST http://localhost:8080/events \
       -H "Authorization: Bearer <USER_토큰>" \
       -H "Content-Type: application/json" \
       -d '{"title":"test"}'
     # 기대: 403
     curl -s -X POST http://localhost:8080/events \
       -H "Authorization: Bearer <USER_토큰>" \
       -H "Content-Type: application/json" \
       -d '{}' | jq .code
     # 기대: "FORBIDDEN"
     ```
  2. `POST /venues`, `DELETE /events/1`, `GET /queue/admin/stats`도 동일하게 403 확인
- **기대 결과**: HTTP 403, `code` = `"FORBIDDEN"`
- **검증 포인트**: `routeValidator.isAdminOnly()` 판별 및 `role != "ADMIN"` 조건 분기 동작

---

### TC-GW-011 — /internal/** 외부 직접 접근 시 404 반환 (InternalPathBlockFilter)

- [ ] 미실행
- **관련 REQ**: REQ-INT-001, REQ-GW-003
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중
- **실행 단계**:
  1. GET 메서드로 `/internal/seats/status/1` 호출
     ```bash
     curl -s -w "%{http_code}" http://localhost:8080/internal/seats/status/1
     # 기대: 404
     ```
  2. POST 메서드로 `/internal/api/test` 호출 — 동일하게 404 확인
  3. 유효한 JWT 포함 시에도 차단 확인
     ```bash
     curl -s -w "%{http_code}" http://localhost:8080/internal/seats/status/1 \
       -H "Authorization: Bearer <유효ADMIN토큰>"
     # 기대: 404 (JWT 검증 전에 라우트 SetStatus=404 적용)
     ```
  4. 깊이 중첩된 경로(`/internal/deep/nested/path`) 및 쿼리파라미터 포함(`/internal/test?param=val`) 케이스도 404 확인
  5. 동일 경로 prefix를 가진 정상 경로가 차단되지 않는지 확인 (없어야 정상)
- **기대 결과**: `/internal/**` 모든 메서드/깊이/쿼리파라미터에서 HTTP 404, 응답 본문 없음
- **검증 포인트**: `block-internal-api` 라우트 `order: -1` 우선순위로 SetStatus=404 적용 여부

---

### TC-GW-012 — Queue Token 필수 경로에서 X-Queue-Token 헤더 누락 시 401 QUEUE_TOKEN_MISSING

- [ ] 미실행
- **관련 REQ**: REQ-GW-016, REQ-QUEUE-010
- **분류**: 예외
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, 유효한 JWT 준비, Redis 블랙리스트 미등록
- **실행 단계**:
  1. Queue Token 필수 5개 엔드포인트 각각에서 헤더 누락 요청
     ```bash
     TOKEN="<유효_USER_JWT>"
     # GET /reservations/seats/{scheduleId}
     curl -s http://localhost:8080/reservations/seats/1 \
       -H "Authorization: Bearer $TOKEN" | jq .code
     # 기대: "QUEUE_TOKEN_MISSING"

     # POST /reservations/hold
     curl -s -X POST http://localhost:8080/reservations/hold \
       -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" -d '{}' | jq .code
     # 기대: "QUEUE_TOKEN_MISSING"

     # POST /payments
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" -d '{}' | jq .code
     # 기대: "QUEUE_TOKEN_MISSING"
     ```
- **기대 결과**: 5개 경로 모두 HTTP 401, `code` = `"QUEUE_TOKEN_MISSING"`
- **검증 포인트**: `QueueTokenWebFilter` 가 JWT 필터 이후(Order+3)에 실행되어 JWT 인증 성공 후 Queue Token 검사

---

### TC-GW-013 — Queue Token 형식 위조 (qr_ prefix 없음, 대문자 UUID, 짧은 값) 시 401 QUEUE_TOKEN_INVALID

- [ ] 미실행
- **관련 REQ**: REQ-GW-016, REQ-QUEUE-010
- **분류**: 보안, 경계값
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중, 유효한 JWT 준비, Redis 블랙리스트 미등록
- **실행 단계**:
  1. `qr_` prefix 없는 UUID
     ```bash
     TOKEN="<유효_USER_JWT>"
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer $TOKEN" \
       -H "X-Queue-Token: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
       -H "Content-Type: application/json" -d '{}' | jq .code
     # 기대: "QUEUE_TOKEN_INVALID"
     ```
  2. `qr_` prefix는 있으나 UUID 대문자 사용 (`qr_XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX`)
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer $TOKEN" \
       -H "X-Queue-Token: qr_$(uuidgen)" | jq .code
     # 기대: "QUEUE_TOKEN_INVALID" (소문자 UUID만 허용)
     ```
  3. `qr_invalid-token-format` (UUID 아님)
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer $TOKEN" \
       -H "X-Queue-Token: qr_invalid-token-format" | jq .code
     # 기대: "QUEUE_TOKEN_INVALID"
     ```
  4. 올바른 형식 `qr_<소문자UUID>` — 필터 통과 확인 (5xx = downstream 부재로 정상)
     ```bash
     curl -s -w "%{http_code}" -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer $TOKEN" \
       -H "X-Queue-Token: qr_$(uuidgen | tr '[:upper:]' '[:lower:]')" | tail -c 3
     # 기대: 5xx (필터 통과, downstream 없음)
     ```
- **기대 결과**: 형식 위조 케이스 모두 HTTP 401, `code` = `"QUEUE_TOKEN_INVALID"`, 정상 형식은 통과
- **검증 포인트**: `QUEUE_TOKEN_PATTERN = Regex("^qr_[0-9a-f]{8}-[0-9a-f]{4}-...$")` 정규식 경계값

---

### TC-GW-014 — Queue Token 불필요 경로(GET /reservations)에서 토큰 없어도 정상 통과

- [ ] 미실행
- **관련 REQ**: REQ-GW-016
- **분류**: 정상
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중, 유효한 JWT 준비, Redis 블랙리스트 미등록
- **실행 단계**:
  1. Queue Token 없이 `GET /reservations` (내 예매 내역 — Queue Token 불필요) 호출
     ```bash
     curl -s -w "%{http_code}" http://localhost:8080/reservations \
       -H "Authorization: Bearer <유효_USER_JWT>"
     # 기대: 5xx (downstream 없음, Queue Token 필터 미개입)
     # 401이면 오류
     ```
  2. Queue Token 없이 `GET /payments/1` (결제 조회 — Queue Token 불필요) 호출 — 동일하게 5xx 확인
- **기대 결과**: Queue Token 없어도 HTTP 5xx (downstream 없어서), 401 아님 — `isQueueTokenRequired()` 가 false 반환
- **검증 포인트**: 응답 코드가 401이 아닌 것, `QueueTokenWebFilter`가 해당 경로를 skip

---

### TC-GW-015 — 허용되지 않은 Origin에서 CORS 요청 시 Access-Control-Allow-Origin 헤더 부재

- [ ] 미실행
- **관련 REQ**: REQ-GW-004
- **분류**: 보안
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중
- **실행 단계**:
  1. 허용되지 않은 Origin으로 GET 요청
     ```bash
     curl -s -D - http://localhost:8080/events \
       -H "Origin: https://evil.com" | grep -i "access-control"
     # 기대: Access-Control-Allow-Origin 헤더 없음
     ```
  2. null origin으로 요청
     ```bash
     curl -s -D - http://localhost:8080/events \
       -H "Origin: null" | grep -i "access-control-allow-origin"
     # 기대: 헤더 없음
     ```
  3. 허용된 origin에서 preflight (OPTIONS) — Access-Control-Allow-Origin 포함 확인
     ```bash
     curl -s -D - -X OPTIONS http://localhost:8080/events \
       -H "Origin: https://ticketing.vercel.app" \
       -H "Access-Control-Request-Method: GET" | grep -i "access-control"
     # 기대: Access-Control-Allow-Origin: https://ticketing.vercel.app
     #        Access-Control-Allow-Credentials: true
     #        Access-Control-Max-Age: 3600
     ```
- **기대 결과**: 미허용 Origin = ACAO 헤더 없음, 허용 Origin = ACAO 헤더 포함, preflight = 200
- **검증 포인트**: `Access-Control-Allow-Origin`, `Access-Control-Allow-Credentials: true`, `Access-Control-Expose-Headers: X-Queue-Token` 포함 여부

---

### TC-GW-016 — 모든 응답에 보안 헤더 5종 포함 확인 (401 오류 응답 포함)

- [ ] 미실행
- **관련 REQ**: REQ-GW-011
- **분류**: 보안
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중
- **실행 단계**:
  1. 토큰 없이 보호 엔드포인트 호출하여 401 응답 헤더 확인
     ```bash
     curl -s -D - http://localhost:8080/users/me | grep -iE "x-content-type|x-frame|strict-transport|x-xss|content-security"
     # 기대 (5개 헤더 모두 포함):
     # X-Content-Type-Options: nosniff
     # X-Frame-Options: DENY
     # Strict-Transport-Security: max-age=31536000; includeSubDomains
     # X-XSS-Protection: 0
     # Content-Security-Policy: default-src 'self'
     ```
  2. 공개 엔드포인트 정상 응답(5xx 포함)에서도 동일 헤더 확인
     ```bash
     curl -s -D - http://localhost:8080/events | grep -iE "x-content-type|x-frame"
     # 기대: X-Content-Type-Options: nosniff, X-Frame-Options: DENY 포함
     ```
- **기대 결과**: 모든 응답(정상/오류 무관)에 5개 보안 헤더 포함 (`SecurityHeadersWebFilter Order+1` 선행 적용 덕분)
- **검증 포인트**: 401/403 오류 응답에서도 보안 헤더 5종 전부 존재

---

### TC-GW-017 — TraceId 생성 및 요청-응답 X-Trace-Id 헤더 전파

- [ ] 미실행
- **관련 REQ**: REQ-GW-009
- **분류**: 정상
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중
- **실행 단계**:
  1. X-Trace-Id 헤더 없이 요청 — 응답에 신규 생성된 traceId 포함 확인
     ```bash
     curl -s -D - http://localhost:8080/users/me | grep -i "x-trace-id"
     # 기대: X-Trace-Id: <UUID 형식>
     ```
  2. X-Trace-Id 헤더를 직접 지정하여 요청 — 동일 값이 응답에 반영 확인
     ```bash
     curl -s -D - http://localhost:8080/users/me \
       -H "X-Trace-Id: my-custom-trace-123"
     # 응답 헤더: X-Trace-Id: my-custom-trace-123
     ```
  3. 오류 응답(401) JSON 본문의 `traceId` 필드와 응답 헤더 `X-Trace-Id` 값 일치 확인
     ```bash
     RESPONSE=$(curl -s -D - http://localhost:8080/users/me)
     HEADER_TRACE=$(echo "$RESPONSE" | grep -i "x-trace-id" | awk '{print $2}' | tr -d '\r')
     BODY_TRACE=$(echo "$RESPONSE" | tail -1 | jq -r .traceId)
     [ "$HEADER_TRACE" = "$BODY_TRACE" ] && echo "MATCH" || echo "MISMATCH"
     # 기대: MATCH
     ```
- **기대 결과**: 응답 헤더 `X-Trace-Id` 와 오류 응답 본문 `traceId` 값 일치, 외부 제공 traceId 재사용
- **검증 포인트**: 헤더-본문 traceId 일치, 유효하지 않은 traceId(특수문자 포함) 제공 시 신규 UUID 생성

---

### TC-GW-018 — 유효하지 않은 X-Trace-Id (특수문자/65자 초과) 제공 시 신규 UUID 생성

- [ ] 미실행
- **관련 REQ**: REQ-GW-009
- **분류**: 엣지, 경계값
- **우선순위**: P2(선택)
- **사전조건**: api-gateway 기동 중
- **실행 단계**:
  1. 허용 패턴 외 특수문자 포함 traceId 전송
     ```bash
     curl -s -D - http://localhost:8080/users/me \
       -H "X-Trace-Id: <script>alert(1)</script>"
     # 응답 X-Trace-Id 가 제공한 값이 아닌 새 UUID인지 확인
     ```
  2. 65자 초과 traceId 전송 (MAX_TRACE_ID_LENGTH=64)
     ```bash
     LONG_ID=$(python3 -c "print('a'*65)")
     curl -s -D - http://localhost:8080/users/me \
       -H "X-Trace-Id: $LONG_ID" | grep -i "x-trace-id"
     # 기대: 응답 X-Trace-Id가 65자 아닌 UUID 형식
     ```
  3. 정확히 64자 alphanumeric traceId — 그대로 반영 확인
     ```bash
     VALID_64=$(python3 -c "print('a'*64)")
     curl -s -D - http://localhost:8080/users/me \
       -H "X-Trace-Id: $VALID_64" | grep -i "x-trace-id"
     # 기대: X-Trace-Id: aaaa...(64자)
     ```
- **기대 결과**: 유효하지 않은 traceId는 무시하고 신규 UUID 생성, 64자 이하 알파뉴메릭+하이픈은 재사용
- **검증 포인트**: `TRACE_ID_PATTERN = Regex("^[a-zA-Z0-9\\-]{1,64}$")` 경계값 검증

---

### TC-GW-019 — user-service 경로에서 10KB 초과 요청 시 413 Payload Too Large 반환

- [ ] 미실행
- **관련 REQ**: REQ-GW-014
- **분류**: 경계값
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중
- **실행 단계**:
  1. 정확히 10240바이트(10KB) 요청 — 통과 확인
     ```bash
     curl -s -w "%{http_code}" -X POST http://localhost:8080/auth/signup \
       -H "Content-Type: text/plain" \
       --data-binary "$(python3 -c "print('x'*10240, end='')")"
     # 기대: 401 또는 5xx (413 아님 — RequestSize 경계: 10240바이트 이하 허용)
     ```
  2. 10241바이트(10KB + 1) 요청 — 413 확인
     ```bash
     curl -s -w "%{http_code}" -X POST http://localhost:8080/auth/signup \
       -H "Content-Type: text/plain" \
       --data-binary "$(python3 -c "print('x'*10241, end='')")"
     # 기대: 413
     ```
  3. `POST /payments` (RequestSize 필터 없음)에 대용량 요청 — 413 아닌 401 확인
     ```bash
     curl -s -w "%{http_code}" -X POST http://localhost:8080/payments \
       -H "Content-Type: text/plain" \
       --data-binary "$(python3 -c "print('x'*20000, end='')")"
     # 기대: 401 (RequestSize 미적용 경로)
     ```
- **기대 결과**: `/auth/**`, `/users/**` 경로 10KB 초과 시 413, 그 외 경로는 413 미발생
- **검증 포인트**: `RequestSize maxSize: 10KB` 필터가 user-service 라우트에만 적용됨을 경계값으로 확인

---

### TC-GW-020 — 다운스트림 서비스 장애 시 Circuit Breaker Fallback 503 응답 및 traceId 포함

- [ ] 미실행
- **관련 REQ**: REQ-GW-006, REQ-GW-017
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중, user-service(8081)는 미기동 상태, 유효한 JWT 준비
- **실행 단계**:
  1. user-service가 내려간 상태에서 보호 엔드포인트 호출
     ```bash
     curl -s -w "\n%{http_code}" http://localhost:8080/users/me \
       -H "Authorization: Bearer <유효_USER_JWT>"
     # 기대: 503
     curl -s http://localhost:8080/users/me \
       -H "Authorization: Bearer <유효_USER_JWT>" | jq .
     # 기대: {"code":"SERVICE_UNAVAILABLE","message":"인증 서비스가 일시적으로 불안정합니다...","timestamp":"...","traceId":"..."}
     ```
  2. `minimumNumberOfCalls`(운영: 20회) 이상 실패 발생 후 Circuit OPEN 전환 확인
     ```bash
     for i in {1..25}; do
       curl -s -w "%{http_code} " http://localhost:8080/users/me \
         -H "Authorization: Bearer <유효_JWT>"
     done
     # OPEN 전환 후에도 즉시 503 응답 (실제 연결 시도 없음)
     ```
  3. Actuator circuitbreakers 상태 확인
     ```bash
     curl http://localhost:9080/actuator/health | jq '.components.circuitBreakers'
     ```
- **기대 결과**: HTTP 503, `code` = `"SERVICE_UNAVAILABLE"`, 서비스별 맞춤 메시지, `traceId` 포함
- **검증 포인트**: FallbackController의 서비스명별 메시지 분기, `traceId` 필드 응답 포함, Actuator CB 상태

---

### TC-GW-021 — Rate Limiting 임계 초과 시 429 Too Many Requests 반환 (전역 IP 기반)

- [ ] 미실행
- **관련 REQ**: REQ-GW-005, REQ-GW-006
- **분류**: 성능, 경계값
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중, Redis 연결 정상, `globalRedisRateLimiter` (replenishRate=50, burstCapacity=100) 설정 확인
- **실행 단계**:
  1. 동일 IP에서 burstCapacity(100)를 초과하는 요청 연속 발송
     ```bash
     for i in {1..120}; do
       CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/events)
       echo "$i: $CODE"
     done | grep 429 | head -5
     # 기대: 101번째 이후 요청에서 429 등장
     ```
  2. 429 응답 헤더에 `X-RateLimit-Remaining`, `X-RateLimit-Retry-After` 포함 확인
     ```bash
     curl -s -D - http://localhost:8080/events | grep -i "ratelimit\|retry-after"
     ```
  3. 1초 대기 후 재요청 시 정상 응답 확인 (토큰 보충)
     ```bash
     sleep 2
     curl -s -w "%{http_code}" http://localhost:8080/events
     # 기대: 502/503 (downstream 없음), 429 아님
     ```
- **기대 결과**: 버스트 100 초과 시 429, Redis `request_rate_limiter.{ip}.*` 키 확인 가능, 토큰 보충 후 정상화
- **검증 포인트**: 429 응답 코드, RateLimit 헤더, Redis에서 토큰 버킷 키 확인 (`redis-cli KEYS "request_rate_limiter.*"`)

---

### TC-GW-022 — /auth/refresh (공개 라우트)에 만료 JWT를 Bearer로 전송해도 통과 (JWT 필터 스킵)

- [ ] 미실행
- **관련 REQ**: REQ-GW-003, REQ-AUTH-012
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: api-gateway 기동 중 (user-service 미기동 가능)
- **실행 단계**:
  1. 만료된 Access Token을 Authorization 헤더에 담아 `/auth/refresh` 호출
     ```bash
     curl -s -w "\n%{http_code}" -X POST http://localhost:8080/auth/refresh \
       -H "Authorization: Bearer <만료된_액세스_토큰>" \
       -H "Content-Type: application/json" \
       -d '{"refreshToken":"<리프레시토큰>"}'
     # 기대: 5xx (downstream 없음) 또는 user-service 응답 — 401 EXPIRED_TOKEN이 아님
     ```
  2. Authorization 헤더 없이 `/auth/refresh` 호출 — 동일하게 JWT 필터 스킵 확인
     ```bash
     curl -s -w "\n%{http_code}" -X POST http://localhost:8080/auth/refresh \
       -H "Content-Type: application/json" \
       -d '{"refreshToken":"invalid"}'
     # 기대: 5xx (JWT 필터가 개입하지 않아 401 아님)
     ```
  3. `POST /auth/login`, `POST /auth/signup` 도 동일하게 JWT 필터 스킵 확인
- **기대 결과**: `/auth/refresh`, `/auth/login`, `/auth/signup`은 JWT 없거나 만료돼도 401 반환하지 않고 downstream 전달
- **검증 포인트**: `RouteValidator.publicRoutes`에 `RouteRule("/auth/refresh", HttpMethod.POST)` 포함, 응답이 401이 아닌 것
