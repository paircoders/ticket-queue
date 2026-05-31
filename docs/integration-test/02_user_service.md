## 02. User Service (인증/회원/JWT)

> **영역 범위**: User Service의 회원가입(reCAPTCHA/CI-DI/중복 검증), 로그인, JWT 발급·갱신·무효화, 프로필 관리, 비밀번호 변경, 회원 탈퇴(Soft Delete), 내부 API 보안을 포함한 인증·회원 관리 전 영역
> **사전 준비**: `cd docker && docker-compose up -d` 로 PostgreSQL(5432), Valkey(6379), Kafka(9092) 기동 후 `./gradlew :user-service:bootRun` 으로 서비스 실행(포트 8081). 테스트용 PortOne 테스트 모드 자격증명 및 reCAPTCHA 테스트 키(`6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI`) 설정 확인.
> **주 실행 수단**: curl REST + gradle test, Redis/DB 상태 검증
> **총 항목 수**: 27

---

### TC-USER-001 — 정상 회원가입: reCAPTCHA > PortOne CI/DI > 저장 전 플로우 성공

- [x] 통과 (2026-05-31, 근거: 단위테스트 AuthServiceTest·AuthTransactionalServiceTest로 reCAPTCHA→PortOne→암호화 저장 플로우 검증, 라이브 DB로 email/name/phone 암호화 저장·복호화(/users/me)·password_hash $2a$·email_hash HMAC 구조 확인. PortOne 본인인증 발급은 외부 콘솔 의존)
- **관련 REQ**: REQ-AUTH-001, REQ-AUTH-003, REQ-AUTH-004, REQ-AUTH-005, REQ-AUTH-014
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: 사용 이력 없는 신규 이메일, PortOne 테스트 모드 유효 `identityVerificationId`, reCAPTCHA 테스트 토큰
- **실행 단계**:
  1. PortOne 테스트 콘솔에서 본인인증 세션 생성 후 `identityVerificationId` 획득
  2. ```bash
     curl -s -X POST http://localhost:8081/auth/signup \
       -H "Content-Type: application/json" \
       -d '{
         "email": "newuser@example.com",
         "password": "Test1234!",
         "name": "홍길동",
         "phone": "010-1234-5678",
         "identityVerificationId": "<PORTONE_ID>",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
  3. DB 확인: `SELECT id, email_hash, password_hash, ci_hash, status FROM user_service.users WHERE email_hash = '<hash>';`
- **기대 결과**: HTTP 201, 응답 바디 `{ "id": "<UUID>", "email": "newuser@example.com", "name": "홍길동" }`
- **검증 포인트**:
  - HTTP 상태 201
  - DB `user_service.users` 에 row 1건 삽입, `status = 'ACTIVE'`
  - `password_hash` 컬럼이 `$2a$` 로 시작(BCrypt), 평문 미저장
  - `email` 컬럼이 평문이 아닌 암호화된 값, `email_hash` 는 HMAC 해시값
  - `ci_hash` unique 제약으로 CI 저장 확인

---

### TC-USER-002 — reCAPTCHA 검증 실패 시 회원가입 차단

- [x] 통과 (2026-05-31, 근거: AuthControllerTest "reCAPTCHA 검증 실패 시 400"·AuthServiceTest "RECAPTCHA_FAILED, processSignup 미호출". 로컬 reCAPTCHA 테스트 시크릿은 항상 success라 라이브 실패 유도 불가 → 단위테스트 근거)
- **관련 REQ**: REQ-AUTH-003
- **분류**: 예외, 보안
- **우선순위**: P0
- **사전조건**: 서비스 정상 기동
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/signup \
       -H "Content-Type: application/json" \
       -d '{
         "email": "test@example.com",
         "password": "Test1234!",
         "name": "테스트",
         "phone": "010-0000-0000",
         "identityVerificationId": "any-id",
         "recaptchaToken": "INVALID_TOKEN"
       }'
     ```
- **기대 결과**: HTTP 400, `{ "code": "RECAPTCHA_FAILED", ... }`
- **검증 포인트**:
  - HTTP 400
  - PortOne API 호출이 발생하지 않았음 (reCAPTCHA가 첫 번째 관문)
  - `user_service.users` 에 신규 row 미삽입

---

### TC-USER-003 — 동일 CI로 중복 가입 시도: 409 DUPLICATE_IDENTITY

- [x] 통과 (2026-05-31, 근거: AuthTransactionalServiceTest "CI 중복 - DUPLICATE_IDENTITY"·AuthControllerTest 409 + DB users_ci_hash_unique 제약 라이브 확인(TC-023))
- **관련 REQ**: REQ-AUTH-004
- **분류**: 예외, 보안
- **우선순위**: P0
- **사전조건**: TC-USER-001 완료로 특정 CI가 이미 DB에 저장된 상태
- **실행 단계**:
  1. 동일 CI를 갖는 새 PortOne 테스트 `identityVerificationId` 획득 (같은 테스트 계정 재인증)
  2. ```bash
     curl -s -X POST http://localhost:8081/auth/signup \
       -H "Content-Type: application/json" \
       -d '{
         "email": "another@example.com",
         "password": "Test1234!",
         "name": "다른이름",
         "phone": "010-9999-8888",
         "identityVerificationId": "<SAME_CI_PORTONE_ID>",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
- **기대 결과**: HTTP 409, `{ "code": "DUPLICATE_IDENTITY", ... }`
- **검증 포인트**:
  - HTTP 409
  - `ci_hash` unique 제약 위반으로 두 번째 row 미삽입
  - `user_service.users` 에 기존 1건만 존재

---

### TC-USER-004 — 이메일 중복 가입 시도: 409 ALREADY_EXISTS_EMAIL

- [x] 통과 (2026-05-31, 근거: AuthTransactionalServiceTest "이메일 중복 - ALREADY_EXISTS_EMAIL"·AuthControllerTest 409 + DB users_email_hash_unique 제약)
- **관련 REQ**: REQ-AUTH-005
- **분류**: 예외
- **우선순위**: P0
- **사전조건**: TC-USER-001 완료로 `newuser@example.com` 가입 완료
- **실행 단계**:
  1. 새 PortOne `identityVerificationId`(다른 CI) 획득
  2. ```bash
     curl -s -X POST http://localhost:8081/auth/signup \
       -H "Content-Type: application/json" \
       -d '{
         "email": "newuser@example.com",
         "password": "AnotherPwd!",
         "name": "다른사람",
         "phone": "010-1111-2222",
         "identityVerificationId": "<DIFFERENT_CI_ID>",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
- **기대 결과**: HTTP 409, `{ "code": "ALREADY_EXISTS_EMAIL", ... }`
- **검증 포인트**:
  - HTTP 409
  - `email_hash` unique 제약 위반으로 신규 row 미삽입

---

### TC-USER-005 — PortOne 본인인증 상태 READY(미완료) 시 가입 차단

- [x] 통과 (2026-05-31, 근거: 신규 단위테스트 PortoneServiceTest "본인인증 실패 - READY 상태이면 PORTONE_VERIFICATION_TIMEOUT" 추가·통과 — status=READY 분기 검증)
- **관련 REQ**: REQ-AUTH-004
- **분류**: 예외, 엣지
- **우선순위**: P1
- **사전조건**: PortOne 테스트 모드에서 인증이 완료되지 않은(READY 상태) `identityVerificationId` 준비
- **실행 단계**:
  1. PortOne 인증 세션 시작 후 완료 전 `identityVerificationId` 사용
  2. ```bash
     curl -s -X POST http://localhost:8081/auth/signup \
       -H "Content-Type: application/json" \
       -d '{
         "email": "pending@example.com",
         "password": "Test1234!",
         "name": "미완료",
         "phone": "010-3333-4444",
         "identityVerificationId": "<READY_STATUS_ID>",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
- **기대 결과**: HTTP 400, `{ "code": "PORTONE_VERIFICATION_TIMEOUT", ... }`
- **검증 포인트**:
  - HTTP 400
  - `PortoneService.verifyIdentity` 에서 `status == "READY"` 분기 처리
  - DB row 미삽입

---

### TC-USER-006 — 필수 필드 누락 시 400 Bad Request

- [x] 통과 (2026-05-31, 근거: 라이브 — identityVerificationId/email 누락·recaptchaToken 빈문자열 모두 HTTP 400 (Bean Validation))
- **관련 REQ**: REQ-AUTH-001
- **분류**: 예외, 경계값
- **우선순위**: P1
- **사전조건**: 서비스 정상 기동
- **실행 단계**:
  1. `identityVerificationId` 누락 요청:
     ```bash
     curl -s -X POST http://localhost:8081/auth/signup \
       -H "Content-Type: application/json" \
       -d '{"email":"x@y.com","password":"Test1234!","name":"홍","phone":"010-0-0000","recaptchaToken":"6LeIxAcT..."}'
     ```
  2. `email` 누락 요청 (별도 curl)
  3. `recaptchaToken` 빈 문자열 요청 (별도 curl)
- **기대 결과**: 각각 HTTP 400, Bean Validation 오류 응답
- **검증 포인트**:
  - 모든 케이스 HTTP 400
  - `@NotBlank`, `@Email` 어노테이션 기반 Validation 동작 확인

---

### TC-USER-007 — 정상 로그인: Access/Refresh Token 발급 및 JWT 클레임 검증

- [x] 통과 (2026-05-31, 근거: 라이브 Gateway 8080 — HTTP 200, alg=HS512, payload{sub,jti,role,email,iat,exp}, exp-iat=3600, refresh_tokens revoked=false·expires_at≈+7일, last_login_at 갱신)
- **관련 REQ**: REQ-AUTH-006, REQ-AUTH-011
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: TC-USER-001 완료로 `newuser@example.com` 가입 완료
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/login \
       -H "Content-Type: application/json" \
       -d '{
         "email": "newuser@example.com",
         "password": "Test1234!",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
  2. 응답의 `accessToken` 을 Base64 디코드하여 payload 확인:
     ```bash
     echo "<ACCESS_TOKEN>" | cut -d. -f2 | base64 -d 2>/dev/null | python3 -m json.tool
     ```
  3. DB 확인: `SELECT id, token_family, revoked, expires_at FROM user_service.refresh_tokens ORDER BY issued_at DESC LIMIT 1;`
- **기대 결과**: HTTP 200, `{ "accessToken": "...", "refreshToken": "...", "expiresIn": 3600, "tokenType": "Bearer" }`
- **검증 포인트**:
  - HTTP 200
  - `accessToken` payload: `sub`(userId UUID), `jti`(UUID), `role`("USER"), `email`, `iat`, `exp`(iat+3600초) 모두 존재
  - `exp - iat = 3600` (Access 1시간)
  - JWT 알고리즘 헤더: `"alg": "HS512"`
  - DB `user_service.refresh_tokens` 에 신규 row, `revoked = false`, `expires_at ≈ now + 7일`
  - `user_service.users.last_login_at` 갱신

---

### TC-USER-008 — 로그인: 비밀번호 불일치 시 401 INVALID_CREDENTIALS

- [x] 통과 (2026-05-31, 근거: 라이브 — HTTP 401 INVALID_CREDENTIALS, login_history success=false/failure_reason=INVALID_PASSWORD)
- **관련 REQ**: REQ-AUTH-006, REQ-AUTH-014
- **분류**: 예외, 보안
- **우선순위**: P0
- **사전조건**: TC-USER-001 완료
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/login \
       -H "Content-Type: application/json" \
       -d '{
         "email": "newuser@example.com",
         "password": "WrongPassword!",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
- **기대 결과**: HTTP 401, `{ "code": "INVALID_CREDENTIALS", ... }`
- **검증 포인트**:
  - HTTP 401
  - 응답에 사용자 존재 여부를 노출하지 않음 (계정 없음과 동일한 에러코드)
  - `user_service.login_history` 에 실패 이력 `reason = 'INVALID_PASSWORD'` 기록

---

### TC-USER-009 — 로그인: 존재하지 않는 이메일 시도 시 정보 노출 방지

- [x] 통과 (2026-05-31, 근거: 라이브 — HTTP 401 INVALID_CREDENTIALS(계정 존재여부 미노출), login_history failure_reason=USER_NOT_FOUND 서버측 기록)
- **관련 REQ**: REQ-AUTH-006
- **분류**: 보안, 예외
- **우선순위**: P0
- **사전조건**: 서비스 정상 기동
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/login \
       -H "Content-Type: application/json" \
       -d '{
         "email": "ghost@nonexistent.com",
         "password": "Test1234!",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
- **기대 결과**: HTTP 401, `{ "code": "INVALID_CREDENTIALS", ... }` — 비밀번호 불일치와 동일한 에러코드
- **검증 포인트**:
  - HTTP 401
  - 에러코드 `INVALID_CREDENTIALS` (계정 존재 여부를 `USER_NOT_FOUND` 등으로 노출하지 않음)
  - `login_history` 에 `reason = 'USER_NOT_FOUND'` 로 서버 측 기록만 남음

---

### TC-USER-010 — 로그인: DELETED 상태 계정 로그인 차단

- [x] 통과 (2026-05-31, 근거: 라이브 — DELETED 계정 로그인 HTTP 401 INVALID_CREDENTIALS, login_history failure_reason=ACCOUNT_DELETED)
- **관련 REQ**: REQ-AUTH-017, REQ-AUTH-006
- **분류**: 예외, 보안
- **우선순위**: P0
- **사전조건**: TC-USER-001 완료 후 해당 계정 탈퇴 처리: `UPDATE user_service.users SET status = 'DELETED', deleted_at = now() WHERE email_hash = '<hash>';`
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/login \
       -H "Content-Type: application/json" \
       -d '{
         "email": "newuser@example.com",
         "password": "Test1234!",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
- **기대 결과**: HTTP 401, `{ "code": "INVALID_CREDENTIALS", ... }`
- **검증 포인트**:
  - HTTP 401
  - `login_history` 에 `reason = 'ACCOUNT_DELETED'` 기록
  - 삭제 계정 존재 여부 노출 없음

---

### TC-USER-011 — 로그아웃: Access Token Redis 블랙리스트 등록 + Refresh Token 폐기

- [x] 통과 (2026-05-31, 근거: 라이브 — 로그아웃 204, Redis token:blacklist:<jti>=1 TTL=3600, refresh_tokens.revoked=true, 블랙리스트 토큰 재요청 401)
- **관련 REQ**: REQ-AUTH-008, REQ-AUTH-010
- **분류**: 정상, 보안
- **우선순위**: P0
- **사전조건**: TC-USER-007 완료로 유효한 `accessToken`, `refreshToken` 보유
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/logout \
       -H "Authorization: Bearer <ACCESS_TOKEN>"
     ```
  2. Redis 확인:
     ```bash
     redis-cli -h localhost -p 6379 GET "token:blacklist:<JTI>"
     ```
  3. DB 확인:
     ```bash
     SELECT revoked, revoked_at FROM user_service.refresh_tokens WHERE access_token_jti = '<JTI>';
     ```
  4. 블랙리스트 등록 후 동일 Access Token으로 보호된 엔드포인트 재요청:
     ```bash
     curl -s -X GET http://localhost:8081/users/me \
       -H "Authorization: Bearer <ACCESS_TOKEN>"
     ```
- **기대 결과**: 로그아웃 204 No Content; Redis key `"token:blacklist:<jti>"` 값 `"1"` TTL ~3600초; DB `revoked = true`, `revoked_at` 설정; 재요청 401
- **검증 포인트**:
  - HTTP 204
  - Redis `token:blacklist:<jti>` 존재 및 TTL이 0초 초과 3600초 이하
  - `user_service.refresh_tokens.revoked = true`
  - 블랙리스트 Access Token 재사용 시 401

---

### TC-USER-012 — 토큰 갱신: RTR 동작 — 신규 토큰 발급 + 기존 Refresh 폐기

- [x] 통과 (2026-05-31, 근거: 라이브 — refresh 200 신규 발급, 기존 refresh revoked=true, token_family 동일 유지)
- **관련 REQ**: REQ-AUTH-009, REQ-AUTH-011
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: TC-USER-007 완료로 유효한 `refreshToken` 보유
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/refresh \
       -H "Content-Type: application/json" \
       -d '{"refreshToken": "<REFRESH_TOKEN>"}'
     ```
  2. DB 확인(기존 토큰 폐기):
     ```bash
     SELECT revoked, revoked_at, token_family FROM user_service.refresh_tokens WHERE refresh_token = '<OLD_REFRESH_TOKEN>';
     ```
  3. DB 확인(신규 토큰, 동일 family):
     ```bash
     SELECT id, token_family, revoked FROM user_service.refresh_tokens ORDER BY issued_at DESC LIMIT 2;
     ```
- **기대 결과**: HTTP 200, 신규 `accessToken` + `refreshToken` 반환
- **검증 포인트**:
  - HTTP 200
  - 기존 `refreshToken` DB row: `revoked = true`, `revoked_at` 설정
  - 신규 row: `revoked = false`, `token_family` 동일 UUID
  - 신규 `accessToken` payload에 새 `jti`(이전과 다름)

---

### TC-USER-013 — 탈취 감지: 폐기된 Refresh Token 재사용 시 동일 Family 전체 무효화

- [x] 통과 (2026-05-31, 근거: 라이브 — #273 수정(REVOKED_REFRESH_TOKEN 403→401) 후 user-service 재빌드. 폐기 refresh 재사용 시 user-service:8081 및 Gateway:8080 양쪽 HTTP 401 `REVOKED_REFRESH_TOKEN` 반환 확인. family 전체 무효화·에러코드는 기존대로 정상. 전체 토큰 에러 패밀리(EXPIRED_TOKEN/INVALID_TOKEN)와 401로 통일. AuthControllerTest 401 동기화 후 --rerun-tasks 통과)
- **관련 REQ**: REQ-AUTH-009
- **분류**: 보안, 멱등성
- **우선순위**: P0
- **사전조건**: TC-USER-012 완료로 이미 `revoked = true` 인 old `refreshToken` 보유
- **실행 단계**:
  1. 이미 폐기된 old `refreshToken` 으로 갱신 재시도:
     ```bash
     curl -s -X POST http://localhost:8081/auth/refresh \
       -H "Content-Type: application/json" \
       -d '{"refreshToken": "<OLD_REVOKED_REFRESH_TOKEN>"}'
     ```
  2. DB 확인 — 동일 `token_family` 의 모든 active token:
     ```bash
     SELECT id, revoked, revoked_at FROM user_service.refresh_tokens WHERE token_family = '<FAMILY_UUID>';
     ```
- **기대 결과**: HTTP 401, `{ "code": "REVOKED_REFRESH_TOKEN", ... }`; 동일 family 내 모든 `revoked = true`
- **검증 포인트**:
  - HTTP 401 `REVOKED_REFRESH_TOKEN`
  - 동일 `token_family` 의 모든 refresh_token row에 `revoked = true`, `revoked_at` 설정
  - 이후 신규 발급된 valid token 으로도 갱신 불가 (family 전체 폐기 확인)

---

### TC-USER-014 — 만료된 Refresh Token으로 갱신 시 401 EXPIRED_TOKEN

- [x] 통과 (2026-05-31, 근거: 라이브 — DB expires_at(UTC) 과거 조작 후 refresh HTTP 401 EXPIRED_TOKEN. ※DB세션 TZ=Asia/Seoul·앱저장 UTC이므로 UTC 기준 조작)
- **관련 REQ**: REQ-AUTH-009, REQ-AUTH-011
- **분류**: 예외, 경계값
- **우선순위**: P1
- **사전조건**: DB에서 직접 `expires_at` 을 과거 시각으로 조작한 refresh_token:
  `UPDATE user_service.refresh_tokens SET expires_at = now() - interval '1 second' WHERE id = '<TOKEN_ID>';`
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/refresh \
       -H "Content-Type: application/json" \
       -d '{"refreshToken": "<EXPIRED_TOKEN>"}'
     ```
- **기대 결과**: HTTP 401, `{ "code": "EXPIRED_TOKEN", ... }`
- **검증 포인트**:
  - HTTP 401 `EXPIRED_TOKEN`
  - JWT 서명은 유효하나 DB 기준 만료 체크(`expiresAt.isAfter(now)` = false)에서 차단

---

### TC-USER-015 — 위조된 서명의 Refresh Token 사용 시 거부

- [x] 통과 (2026-05-31, 근거: 라이브 — 위조 서명 refresh HTTP 401 INVALID_TOKEN)
- **관련 REQ**: REQ-AUTH-009, REQ-AUTH-011
- **분류**: 보안, 예외
- **우선순위**: P0
- **사전조건**: 서비스 정상 기동
- **실행 단계**:
  1. 임의 문자열을 Refresh Token으로 사용:
     ```bash
     curl -s -X POST http://localhost:8081/auth/refresh \
       -H "Content-Type: application/json" \
       -d '{"refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.INVALID_SIGNATURE"}'
     ```
- **기대 결과**: HTTP 401, `{ "code": "INVALID_TOKEN", ... }`
- **검증 포인트**:
  - HTTP 401 `INVALID_TOKEN`
  - `JwtTokenProvider.validateAndParseRefreshToken` 에서 `JwtException` 으로 처리
  - DB 조회 없이 빠른 실패(fast-fail)

---

### TC-USER-016 — Access Token을 Refresh Token 엔드포인트에 제출 시 거부

- [x] 통과 (2026-05-31, 근거: 라이브 — Access Token을 /auth/refresh에 제출 시 HTTP 401 INVALID_TOKEN (type!=refresh 거부))
- **관련 REQ**: REQ-AUTH-009
- **분류**: 보안, 예외
- **우선순위**: P1
- **사전조건**: TC-USER-007 완료로 유효한 `accessToken` 보유
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/refresh \
       -H "Content-Type: application/json" \
       -d '{"refreshToken": "<ACCESS_TOKEN>"}'
     ```
- **기대 결과**: HTTP 401, `{ "code": "INVALID_TOKEN", ... }`
- **검증 포인트**:
  - HTTP 401 `INVALID_TOKEN`
  - `validateAndParseRefreshToken` 에서 `claims["type"] != "refresh"` 체크로 거부

---

### TC-USER-017 — 프로필 조회: 본인 Access Token으로 정상 조회

- [x] 통과 (2026-05-31, 근거: 라이브 Gateway — HTTP 200, email/name/phone 복호화 평문 반환, role=USER)
- **관련 REQ**: REQ-AUTH-012
- **분류**: 정상
- **우선순위**: P1
- **사전조건**: TC-USER-007 완료로 유효한 `accessToken` 보유
- **실행 단계**:
  1. ```bash
     curl -s -X GET http://localhost:8081/users/me \
       -H "Authorization: Bearer <ACCESS_TOKEN>"
     ```
- **기대 결과**: HTTP 200, `{ "id": "<UUID>", "email": "newuser@example.com", "name": "홍길동", "phone": "010-1234-5678", "role": "USER", "createdAt": "..." }`
- **검증 포인트**:
  - HTTP 200
  - 응답 `email`, `name`, `phone` 이 평문(복호화된 값)
  - `role = "USER"`
  - Authorization 헤더 미포함 요청 시 401

---

### TC-USER-018 — 프로필 조회: Authorization 헤더 없을 시 401

- [x] 통과 (2026-05-31, 근거: 라이브 — Authorization 없이 /users/me HTTP 401 (X-User-Id 부재))
- **관련 REQ**: REQ-AUTH-012
- **분류**: 보안, 예외
- **우선순위**: P1
- **사전조건**: 서비스 정상 기동
- **실행 단계**:
  1. ```bash
     curl -s -X GET http://localhost:8081/users/me
     ```
- **기대 결과**: HTTP 401
- **검증 포인트**:
  - HTTP 401
  - `GatewayAuthFilter` 에서 `X-User-Id` 헤더 부재로 인증 실패 처리

---

### TC-USER-019 — 비밀번호 변경: 기존 비밀번호 불일치 시 401

- [x] 통과 (2026-05-31, 근거: 라이브 — 기존 비밀번호 불일치 HTTP 401 INVALID_CREDENTIALS, password_hash 불변)
- **관련 REQ**: REQ-AUTH-016, REQ-AUTH-014
- **분류**: 예외, 보안
- **우선순위**: P1
- **사전조건**: TC-USER-007 완료로 유효한 `accessToken` 보유
- **실행 단계**:
  1. ```bash
     curl -s -X PUT http://localhost:8081/users/me/password \
       -H "Authorization: Bearer <ACCESS_TOKEN>" \
       -H "Content-Type: application/json" \
       -d '{
         "currentPassword": "WrongCurrentPassword!",
         "newPassword": "NewPassword123!"
       }'
     ```
- **기대 결과**: HTTP 401, `{ "code": "INVALID_CREDENTIALS", ... }`
- **검증 포인트**:
  - HTTP 401 `INVALID_CREDENTIALS`
  - DB `password_hash` 변경 없음: 기존 BCrypt 해시 그대로 유지
  - `passwordEncoder.matches(wrong, hash) = false` 분기

---

### TC-USER-020 — 비밀번호 변경 성공: 신규 BCrypt 해시로 교체 및 재로그인 확인

- [x] 통과 (2026-05-31, 근거: 라이브 — 변경 204, 구 비밀번호 로그인 401·신 비밀번호 200, password_hash 변경·$2a$ BCrypt)
- **관련 REQ**: REQ-AUTH-016, REQ-AUTH-014
- **분류**: 정상, 보안
- **우선순위**: P1
- **사전조건**: TC-USER-007 완료로 유효한 `accessToken` 보유
- **실행 단계**:
  1. 비밀번호 변경:
     ```bash
     curl -s -X PUT http://localhost:8081/users/me/password \
       -H "Authorization: Bearer <ACCESS_TOKEN>" \
       -H "Content-Type: application/json" \
       -d '{"currentPassword": "Test1234!", "newPassword": "NewPass999!"}'
     ```
  2. 구 비밀번호로 로그인 시도 (실패 확인):
     ```bash
     curl -s -X POST http://localhost:8081/auth/login \
       -H "Content-Type: application/json" \
       -d '{"email":"newuser@example.com","password":"Test1234!","recaptchaToken":"6LeIxAcT..."}'
     ```
  3. 신규 비밀번호로 로그인 시도 (성공 확인)
  4. DB: `SELECT password_hash FROM user_service.users WHERE email_hash = '<hash>';`
- **기대 결과**: 변경 204; 구 비밀번호 로그인 401; 신규 비밀번호 로그인 200
- **검증 포인트**:
  - 변경 후 `password_hash` 가 `$2a$` BCrypt 형식의 새 값
  - 구 hash 와 신규 hash 다름 확인
  - 평문 저장 없음

---

### TC-USER-021 — 프로필 수정: 이름/전화번호 암호화 저장 확인

- [x] 통과 (2026-05-31, 근거: 라이브 — PATCH 200, DB name/phone 암호화 저장(평문 아님), phone_hash 갱신)
- **관련 REQ**: REQ-AUTH-013
- **분류**: 정상
- **우선순위**: P1
- **사전조건**: TC-USER-007 완료로 유효한 `accessToken` 보유
- **실행 단계**:
  1. ```bash
     curl -s -X PATCH http://localhost:8081/users/me \
       -H "Authorization: Bearer <ACCESS_TOKEN>" \
       -H "Content-Type: application/json" \
       -d '{"name": "김철수", "phone": "010-9876-5432"}'
     ```
  2. DB 확인:
     ```bash
     SELECT name, phone, phone_hash FROM user_service.users WHERE id = '<USER_ID>';
     ```
- **기대 결과**: HTTP 200, `{ "id": "...", "name": "김철수", "phone": "010-9876-5432" }`
- **검증 포인트**:
  - HTTP 200
  - DB `name` 컬럼: 평문 "김철수" 아닌 암호화 값
  - DB `phone` 컬럼: 평문 아닌 암호화 값
  - DB `phone_hash` 갱신

---

### TC-USER-022 — 회원 탈퇴: Soft Delete 상태 전이 + 토큰 전면 폐기

- [x] 통과 (2026-05-31, 근거: 라이브 — 탈퇴 204, status=DELETED·deleted_at 설정, active refresh_token=0, Redis 블랙리스트 등록, 재접근 401)
- **관련 REQ**: REQ-AUTH-017, REQ-AUTH-008
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: TC-USER-007 완료로 유효한 `accessToken`, `refreshToken` 보유
- **실행 단계**:
  1. ```bash
     curl -s -X DELETE http://localhost:8081/users/me \
       -H "Authorization: Bearer <ACCESS_TOKEN>"
     ```
  2. DB 확인:
     ```bash
     SELECT status, deleted_at FROM user_service.users WHERE id = '<USER_ID>';
     SELECT COUNT(*) FROM user_service.refresh_tokens WHERE user_id = '<USER_ID>' AND revoked = false;
     ```
  3. Redis 확인:
     ```bash
     redis-cli GET "token:blacklist:<ACCESS_JTI>"
     ```
  4. 탈퇴 후 `accessToken` 으로 프로필 조회:
     ```bash
     curl -s -X GET http://localhost:8081/users/me -H "Authorization: Bearer <ACCESS_TOKEN>"
     ```
- **기대 결과**: 탈퇴 204; DB `status = 'DELETED'`, `deleted_at` 설정; active refresh_token count = 0; Redis blacklist 등록; 이후 프로필 조회 401
- **검증 포인트**:
  - DB `status = 'DELETED'`, `deleted_at` 설정(물리 삭제 없음)
  - 모든 refresh_tokens `revoked = true`
  - Redis `token:blacklist:<jti>` 존재
  - 탈퇴 계정으로 재로그인 시 401

---

### TC-USER-023 — 탈퇴 후 동일 CI로 재가입 차단 여부 확인

- [x] 통과 (2026-05-31, 근거: 라이브 — Soft Delete 계정 ci_hash로 신규 INSERT 시 users_ci_hash_unique 위반(409 DUPLICATE_IDENTITY 동치). PortOne 엔드포인트는 외부 의존)
- **관련 REQ**: REQ-AUTH-004, REQ-AUTH-017
- **분류**: 엣지, 보안
- **우선순위**: P1
- **사전조건**: TC-USER-022 완료로 탈퇴 계정 존재 (`status = 'DELETED'`)
- **실행 단계**:
  1. 동일 CI 소유자가 새 이메일로 재가입 시도:
     ```bash
     curl -s -X POST http://localhost:8081/auth/signup \
       -H "Content-Type: application/json" \
       -d '{
         "email": "rejoin@example.com",
         "password": "Test1234!",
         "name": "재가입",
         "phone": "010-0000-1111",
         "identityVerificationId": "<SAME_CI_PORTONE_ID>",
         "recaptchaToken": "6LeIxAcTAAAAAJcZVRqyHh71UMIEGNQ_MXjiZKhI"
       }'
     ```
- **기대 결과**: HTTP 409, `{ "code": "DUPLICATE_IDENTITY", ... }` (Soft Delete 후에도 ci_hash unique 제약 유지)
- **검증 포인트**:
  - HTTP 409 `DUPLICATE_IDENTITY`
  - `user_service.users` 에 신규 row 미삽입
  - Soft Delete 계정의 `ci_hash` 가 unique 제약에 영향을 줌

---

### TC-USER-024 — 로그인 중 reCAPTCHA 서비스 장애: ExternalSystemException 처리

- [x] 통과 (2026-05-31, 근거: AuthServiceTest "reCAPTCHA 서비스 장애 - ExternalSystemException 전파·RECAPTCHA_SERVICE_ERROR 이력"·RecaptchaServiceTest. 라이브는 서비스 재기동 필요로 단위테스트 근거)
- **관련 REQ**: REQ-AUTH-003, REQ-AUTH-006
- **분류**: 예외, 엣지
- **우선순위**: P1
- **사전조건**: `recaptcha.url` 환경변수를 응답 없는 URL(`http://localhost:19999`)로 임시 변경 후 재기동
- **실행 단계**:
  1. ```bash
     curl -s -X POST http://localhost:8081/auth/login \
       -H "Content-Type: application/json" \
       -d '{"email":"newuser@example.com","password":"Test1234!","recaptchaToken":"any"}'
     ```
- **기대 결과**: HTTP 5xx (502 또는 503), `{ "code": "RECAPTCHA_SERVICE_ERROR", ... }`
- **검증 포인트**:
  - `ExternalSystemException` 이 `RECAPTCHA_SERVICE_ERROR` 로 매핑
  - CircuitBreaker `recaptcha` 의 OPEN 전환 로그 확인
  - `login_history` 에 `reason = 'RECAPTCHA_SERVICE_ERROR'` 기록

---

### TC-USER-025 — 내부 API 보안: /internal/** X-Service-Api-Key 미포함 시 401

- [x] 통과 (2026-05-31, 근거: 라이브 — Gateway /internal/users/me 404(외부 차단), 직접 8081 무키/오류키 401 + common-web InternalApiAuthInterceptorTest(유효키 200))
- **관련 REQ**: REQ-INT-001, REQ-INT-005, REQ-INT-008
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: user-service 정상 기동(포트 8081), API Gateway 정상 기동(포트 8080)
- **실행 단계**:
  1. API Gateway 경유 요청 (외부 차단 확인):
     ```bash
     curl -s -X GET http://localhost:8080/internal/users/me
     ```
  2. user-service 직접 요청, 헤더 없음:
     ```bash
     curl -s -X GET http://localhost:8081/internal/users/me
     ```
  3. user-service 직접 요청, 잘못된 키:
     ```bash
     curl -s -X GET http://localhost:8081/internal/users/me \
       -H "X-Service-Api-Key: wrong-key-value"
     ```
- **기대 결과**: (1) Gateway: 404 (라우팅 차단); (2) Service 직접: 401; (3) Service 직접 잘못된 키: 401
- **검증 포인트**:
  - Gateway: `/internal/**` 라우팅 규칙 없음으로 404 응답 확인
  - Service 직접: `X-Service-Api-Key` 미포함/불일치 시 401
  - 올바른 `INTERNAL_API_KEY=local-dev-internal-api-key` 포함 시에만 정상 응답

---

### TC-USER-026 — JWT HS512 알고리즘: alg=none 또는 HS256 토큰 거부

- [x] 통과 (2026-05-31, 근거: 라이브 — alg=none·HS256 위조 토큰 모두 Gateway에서 HTTP 401 + 신규 단위테스트 JwtTokenProviderTest(alg=none/HS256 거부·HS512 정상))
- **관련 REQ**: REQ-AUTH-011
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: 서비스 정상 기동
- **실행 단계**:
  1. `alg: none` 위조 토큰 생성 (서명 없음):
     ```bash
     # header: {"alg":"none"}, payload: {"sub":"<any-uuid>","role":"ADMIN","exp":9999999999}
     FAKE_TOKEN="eyJhbGciOiJub25lIn0.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJyb2xlIjoiQURNSU4iLCJleHAiOjk5OTk5OTk5OTl9."
     curl -s -X GET http://localhost:8081/users/me \
       -H "Authorization: Bearer $FAKE_TOKEN"
     ```
  2. HS256 서명 위조 토큰으로 시도 (별도 curl)
- **기대 결과**: 모두 HTTP 401, `{ "code": "INVALID_TOKEN", ... }`
- **검증 포인트**:
  - HTTP 401 `INVALID_TOKEN`
  - `JwtTokenProvider.parseAccessTokenJti` 의 알고리즘 검증(`header.algorithm != "HS512"`) 동작
  - JJWT 0.12.6 의 `JwtException` 으로 처리

---

### TC-USER-027 — 동시 로그인: 동일 계정 복수 Refresh Token Family 독립성 확인

- [x] 통과 (2026-05-31, 근거: 라이브 — 동시 로그인 2개 token_family 독립 발급, family A 탈취감지 전체 폐기 시 family B active=1 유지)
- **관련 REQ**: REQ-AUTH-009
- **분류**: 동시성
- **우선순위**: P1
- **사전조건**: TC-USER-001 완료
- **실행 단계**:
  1. 기기 A에서 로그인 (refreshToken-A, family-A 발급):
     ```bash
     curl -s -X POST http://localhost:8081/auth/login \
       -H "Content-Type: application/json" \
       -d '{"email":"newuser@example.com","password":"Test1234!","recaptchaToken":"6LeIxAcT..."}'
     # -> 응답에서 REFRESH_A 저장
     ```
  2. 기기 B에서 로그인 (refreshToken-B, family-B 발급, 별도 curl 실행)
  3. 기기 A Refresh Token으로 갱신:
     ```bash
     curl -s -X POST http://localhost:8081/auth/refresh -d '{"refreshToken": "<REFRESH_A>"}'
     ```
  4. 기기 B Refresh Token 재사용 탐지 시뮬레이션 (REFRESH_B를 폐기 후 재요청)
  5. DB 확인: `SELECT token_family, revoked FROM user_service.refresh_tokens WHERE user_id = '<USER_ID>';`
- **기대 결과**: family-A 탈취 감지가 family-B에 영향 없음; family 간 독립 폐기
- **검증 포인트**:
  - family-A 전체 폐기 시 family-B row의 `revoked` 는 false 유지
  - `revokeAllActiveByTokenFamily` 쿼리가 지정 family만 대상으로 함
