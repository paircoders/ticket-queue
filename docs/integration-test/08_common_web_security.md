## 08. 공통 보안 (내부 API/암호화/예외/Feign)

> **영역 범위**: common-web/common-core 모듈의 내부 API 인증(X-Service-Api-Key), GatewayAuthFilter, AES-256-GCM 암복호화, HMAC-SHA256, GlobalExceptionHandler, FeignErrorDecoder, PortoneTokenService/FallbackFactory 통합 검증
> **사전 준비**: `cd docker && docker-compose up -d` 로 인프라 기동, `INTERNAL_API_KEY=local-dev-internal-api-key` 환경변수 설정, 각 서비스 bootRun 실행 (reservation-service, event-service 최소 필요)
> **주 실행 수단**: `./gradlew :common-core:test :common-web:test` 단위 테스트 + `./gradlew integrationTest` + curl 기반 HTTP 검증
> **총 항목 수**: 18

---

### TC-SEC-001 — X-Service-Api-Key 정상 인증 통과

- [x] 통과 (2026-05-31, 근거: 단위테스트 PASS (BUILD SUCCESSFUL); curl 응답 404(SCHEDULE_NOT_FOUND) — 401 아님, 인증 통과 확인)
- **관련 REQ**: REQ-INT-001
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: event-service 또는 reservation-service 가 `INTERNAL_API_KEY=local-dev-internal-api-key` 로 기동된 상태
- **실행 단계**:
  1. `./gradlew :common-web:test --tests "com.ticketqueue.common.security.InternalApiKeyValidatorTest"` 실행
  2. "올바른 키를 전달하면 예외 없이 통과한다" 케이스가 PASS 인지 확인
  3. curl 보조 확인: `curl -s -o /dev/null -w "%{http_code}" -H "X-Service-Api-Key: local-dev-internal-api-key" http://localhost:8082/internal/seats/status/1`
- **기대 결과**: 단위 테스트 PASS; curl 응답 200 또는 404(리소스 없음) — 401 아님
- **검증 포인트**: HTTP 응답 코드가 401이 아닌 것으로 인증 통과 확인; 단위 테스트 콘솔 출력 `BUILD SUCCESSFUL`

---

### TC-SEC-002 — X-Service-Api-Key 헤더 누락 시 401 거부

- [x] 통과 (2026-05-31, 근거: curl 응답 401 + INTERNAL_API_UNAUTHORIZED; 단위테스트 '헤더가 없는 경우' PASS)
- **관련 REQ**: REQ-INT-001, REQ-INT-002
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: event-service 기동, `INTERNAL_API_KEY` 환경변수 설정됨
- **실행 단계**:
  1. `curl -s -w "\n%{http_code}" http://localhost:8082/internal/seats/status/1`
  2. 응답 body의 `code` 필드 확인
- **기대 결과**: HTTP 401, body `{"code":"INTERNAL_API_UNAUTHORIZED","message":"내부 API 인증에 실패했습니다.", ...}`
- **검증 포인트**: `code` == `"INTERNAL_API_UNAUTHORIZED"`, HTTP status == 401; 단위 테스트: `InternalApiKeyValidatorTest` "헤더가 없는 경우" 케이스 PASS

---

### TC-SEC-003 — X-Service-Api-Key 값 불일치 시 401 거부

- [x] 통과 (2026-05-31, 근거: curl 응답 401 + INTERNAL_API_UNAUTHORIZED; 로그 keyPresent=true 출력; 단위테스트 PASS)
- **관련 REQ**: REQ-INT-001, REQ-INT-002
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: event-service 기동, 올바른 키는 `local-dev-internal-api-key`
- **실행 단계**:
  1. `curl -s -w "\n%{http_code}" -H "X-Service-Api-Key: wrong-key-value" http://localhost:8082/internal/seats/status/1`
  2. 단위 테스트: `InternalApiKeyValidatorTest` "키가 다른 경우" 케이스 확인
- **기대 결과**: HTTP 401, `code` == `"INTERNAL_API_UNAUTHORIZED"`
- **검증 포인트**: 응답 JSON에서 `code` 필드 값; 서비스 로그에 `[INTERNAL_API_AUTH_FAILED] keyPresent=true` 출력 확인

---

### TC-SEC-004 — X-Service-Api-Key 빈 문자열/공백 헤더 거부

- [x] 통과 (2026-05-31, 근거: curl 응답 401 + INTERNAL_API_UNAUTHORIZED; 단위테스트 '헤더가 빈 문자열/공백' PASS)
- **관련 REQ**: REQ-INT-001
- **분류**: 경계값
- **우선순위**: P1(중요)
- **사전조건**: event-service 기동
- **실행 단계**:
  1. 빈 문자열: `curl -s -w "\n%{http_code}" -H "X-Service-Api-Key: " http://localhost:8082/internal/seats/status/1`
  2. 단위 테스트: `InternalApiKeyValidatorTest` "헤더가 빈 문자열인 경우" 케이스 확인
  3. 서버 미설정 시나리오: `InternalApiKeyValidatorTest` "내부 API 키가 설정되지 않은 경우 (blank)" 케이스 확인
- **기대 결과**: HTTP 401 반환; 서버 키 미설정 시 `IllegalStateException("Internal API key is not configured")` 발생
- **검증 포인트**: HTTP 401; 단위 테스트 "공백 문자열도 IllegalStateException" 케이스 PASS

---

### TC-SEC-005 — 타이밍 어택 방어: 길이 상이/동일 키 상수 시간 비교

- [x] 통과 (2026-05-31, 근거: 단위테스트 Timing Attack 방어 케이스 2개 모두 PASS; 소스코드 MessageDigest.isEqual 확인 (line 31))
- **관련 REQ**: REQ-INT-002
- **분류**: 보안
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경
- **실행 단계**:
  1. `./gradlew :common-web:test --tests "com.ticketqueue.common.security.InternalApiKeyValidatorTest"`
  2. "Timing Attack 방어 — 길이가 다른 키" 케이스 확인
  3. "Timing Attack 방어 — 길이가 같고 내용이 다른 키" 케이스 확인
  4. `InternalApiKeyValidator` 소스에서 `MessageDigest.isEqual` 사용 여부 코드 리뷰 확인
- **기대 결과**: 두 케이스 모두 `INTERNAL_API_UNAUTHORIZED` BusinessException; 검증 코드가 `==` 연산자가 아닌 `MessageDigest.isEqual` 을 사용함
- **검증 포인트**: 단위 테스트 PASS; 소스 코드 `InternalApiKeyValidator.kt` 31번 라인 `MessageDigest.isEqual` 확인

---

### TC-SEC-006 — GatewayAuthFilter: 신뢰 헤더(X-User-Id + X-User-Role) 기반 SecurityContext 설정

- [x] 통과 (2026-05-31, 근거: 단위테스트 ROLE_USER/ROLE_ADMIN SecurityContext 설정 케이스 PASS; auth.name 및 authorities 검증 통과)
- **관련 REQ**: REQ-GW-002
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: 단위 테스트 환경 (`GatewayAuthFilterTest`)
- **실행 단계**:
  1. `./gradlew :common-web:test --tests "com.ticketqueue.common.security.GatewayAuthFilterTest"`
  2. "X-User-Id, X-User-Role(USER) 헤더가 있는 경우" 케이스 확인
  3. "X-User-Id, X-User-Role(ADMIN) 헤더가 있는 경우" 케이스 확인
- **기대 결과**: SecurityContext에 `UsernamePasswordAuthenticationToken`이 설정되며, `auth.name == userId`, authorities에 `ROLE_USER` 또는 `ROLE_ADMIN` 포함
- **검증 포인트**: `SecurityContextHolder.getContext().authentication` not null; `auth.authorities` 목록에 올바른 ROLE 포함

---

### TC-SEC-007 — GatewayAuthFilter: 허용되지 않은 Role(SUPERADMIN 등) 헤더 인젝션 차단

- [x] 통과 (2026-05-31, 근거: 단위테스트 SUPERADMIN 헤더 인젝션 방어 케이스 PASS; SecurityContext authentication == null 확인)
- **관련 REQ**: REQ-GW-002
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: 단위 테스트 환경
- **실행 단계**:
  1. `./gradlew :common-web:test --tests "com.ticketqueue.common.security.GatewayAuthFilterTest"`
  2. "X-User-Role이 허용되지 않은 값인 경우 (SUPERADMIN) — 헤더 인젝션 방어" 케이스 확인
  3. 추가 검증: `X-User-Role: SYSTEM`, `X-User-Role: ROOT` 등 임의 값으로 MockHttpServletRequest 구성 후 SecurityContext null 확인
- **기대 결과**: SecurityContext authentication이 null (인증 미설정); `ALLOWED_ROLES = setOf("USER", "ADMIN")` 외 값은 모두 무시됨
- **검증 포인트**: `SecurityContextHolder.getContext().authentication shouldBe null`; filterChain.doFilter()는 여전히 호출됨 (요청 차단 아님, 단순 미설정)

---

### TC-SEC-008 — GatewayAuthFilter: 헤더 일부 누락 시 SecurityContext 미설정

- [x] 통과 (2026-05-31, 근거: 단위테스트 X-User-Id 단독/X-User-Role 단독/두 헤더 모두 없는 경우 3개 케이스 PASS; filterChain.doFilter() 항상 호출)
- **관련 REQ**: REQ-GW-002
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경
- **실행 단계**:
  1. `GatewayAuthFilterTest` 내 "X-User-Id만 있고 X-User-Role 없는 경우" 케이스 실행
  2. "X-User-Role만 있고 X-User-Id 없는 경우" 케이스 실행
  3. "두 헤더가 모두 없는 경우" 케이스 실행
- **기대 결과**: 세 케이스 모두 SecurityContext authentication == null
- **검증 포인트**: 단위 테스트 PASS; filterChain이 차단 없이 호출됨으로써 SecurityConfig 권한 규칙에 위임됨을 확인

---

### TC-SEC-009 — AES-256-GCM 암복호화 라운드트립 및 IV 랜덤성

- [x] 통과 (2026-05-31, 근거: 단위테스트 10개 케이스 PASS; 라운드트립(한글/특수문자/1000자+), IV 랜덤성, Base64 구조 12바이트+ 모두 검증)
- **관련 REQ**: 해당 없음
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: 단위 테스트 환경 (`EncryptionUtilsTest`)
- **실행 단계**:
  1. `./gradlew :common-core:test --tests "com.ticketqueue.common.util.EncryptionUtilsTest"`
  2. "encrypt 후 decrypt시 원본과 동일" 케이스 확인
  3. "같은 평문을 2회 암호화시 다른 결과 반환 (IV 랜덤성)" 케이스 확인
  4. "한글 텍스트 라운드트립", "특수문자 라운드트립", "긴 문자열(1000자 이상) 라운드트립" 케이스 확인
- **기대 결과**: 모든 라운드트립 테스트 PASS; 동일 평문 2회 암호화 결과가 서로 다름 (IV 12바이트 랜덤); 복호화 시 원본 일치
- **검증 포인트**: `assertEquals(plainText, decrypted)`; `assertNotEquals(encrypted1, encrypted2)`; Base64 디코딩 후 크기 > 12바이트 (IV+CipherText 구조 확인)

---

### TC-SEC-010 — AES-256-GCM: 암호문 1바이트 변조 시 GCM 인증 태그 실패

- [x] 통과 (2026-05-31, 근거: 단위테스트 PASS; corruptedBytes[15] 1바이트 변조 → AEADBadTagException; 잘못된 키 → AEADBadTagException; 잘못된 Base64 → IllegalArgumentException)
- **관련 REQ**: 해당 없음
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: 단위 테스트 환경
- **실행 단계**:
  1. `./gradlew :common-core:test --tests "com.ticketqueue.common.util.EncryptionUtilsTest"`
  2. "손상된 암호문 복호화시 예외 발생" 케이스 확인 (corruptedBytes[15] 1바이트 변조 후 decrypt)
  3. "잘못된 키로 복호화시 예외 발생" 케이스 확인 (다른 256-bit AES 키 사용)
  4. "잘못된 Base64 문자열 복호화시 예외 발생" 케이스 확인
- **기대 결과**: `AEADBadTagException` 발생 (1바이트 변조 및 키 불일치 케이스); `IllegalArgumentException` 발생 (잘못된 Base64)
- **검증 포인트**: `assertThrows<AEADBadTagException>` 두 케이스 PASS; IV 바이트(0~11번)가 아닌 암호문/태그 영역(12번 이후) 변조에도 GCM 인증 실패 확인

---

### TC-SEC-011 — AES-256-GCM: IV 영역 변조 시에도 GCM 인증 태그 실패

- [x] 통과 (2026-05-31, 근거: 신규 테스트 추가 및 실행 PASS; bytes[5](IV 영역) 변조 후 decrypt 호출 → AEADBadTagException 발생 확인)
- **관련 REQ**: 해당 없음
- **분류**: 보안
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경, `EncryptionUtils` 직접 호출 테스트 작성
- **실행 단계**:
  1. `EncryptionUtils.encrypt("test", key)` 로 암호문 생성
  2. Base64 디코딩 후 `bytes[5]`(IV 내부) 를 `(bytes[5] + 1).toByte()` 로 변조
  3. 변조된 bytes를 Base64 인코딩 후 `EncryptionUtils.decrypt(corrupted, key)` 호출
  4. `assertThrows<AEADBadTagException>` 로 예외 확인
- **기대 결과**: IV 변조 시에도 GCM 인증 태그가 다른 IV로 재계산되어 `AEADBadTagException` 발생
- **검증 포인트**: IV가 변조되면 복호화 시 GCM 태그 검증 실패; 구조상 `byteBuffer[0..11]` = IV, `byteBuffer[12..]` = CipherText+Tag

---

### TC-SEC-012 — HMAC-SHA256: 결정론적 해시 및 salt 변경 시 다른 결과

- [x] 통과 (2026-05-31, 근거: 단위테스트 4개 케이스 PASS; 결정론적 해시, salt 변경 시 다른 결과, 길이 44자, 빈 문자열 정상 반환)
- **관련 REQ**: 해당 없음
- **분류**: 정상 | 경계값
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경 (`HashUtilsTest`)
- **실행 단계**:
  1. `./gradlew :common-core:test --tests "com.ticketqueue.common.util.HashUtilsTest"`
  2. "동일 입력과 salt로 동일 결과 생성 (결정론적)" 케이스 확인
  3. "같은 텍스트, 다른 salt는 다른 해시 결과 생성" 케이스 확인
  4. "SHA256 결과 길이는 Base64로 44자" 케이스 확인
  5. "빈 문자열 해싱은 정상 반환" 케이스 확인
- **기대 결과**: 동일 (text, salt) 입력 → 동일 해시; 다른 salt → 다른 해시; 결과 길이 항상 44자 (HMAC-SHA256 = 32bytes → Base64 44chars)
- **검증 포인트**: `assertEquals(hashed1, hashed2)` (결정론적); `assertNotEquals(hashed1, hashed2)` (salt 다름); `assertEquals(44, hashed.length)`

---

### TC-SEC-013 — GlobalExceptionHandler: BusinessException → 표준 ErrorResponse 포맷 검증

- [x] 통과 (2026-05-31, 근거: 단위테스트 PASS; BusinessException → HTTP status/code 일치, 커스텀 메시지 우선, traceId non-null 검증)
- **관련 REQ**: 해당 없음
- **분류**: 정상 | 예외
- **우선순위**: P0(필수/핵심)
- **사전조건**: 단위 테스트 환경 (`GlobalExceptionHandlerTest`)
- **실행 단계**:
  1. `./gradlew :common-core:test --tests "com.ticketqueue.common.exception.GlobalExceptionHandlerTest"`
  2. "BusinessException - errorCode의 status와 code를 반환한다" 케이스 확인
  3. "BusinessException - 커스텀 메시지를 반환한다" 케이스 확인
  4. ErrorResponse 응답 구조 확인: `code`, `message`, `timestamp`, `traceId` 필드 모두 존재
- **기대 결과**: HTTP status는 `ErrorCode.status`와 일치; `code` 필드는 `ErrorCode.code` 문자열; `message`는 커스텀 메시지 우선; `traceId` 필드 non-null
- **검증 포인트**: `response.statusCode shouldBe HttpStatus.UNAUTHORIZED`; `response.body!!.code shouldBe "UNAUTHORIZED"`; `response.body!!.traceId` 존재 (MDC 또는 UUID)

---

### TC-SEC-014 — GlobalExceptionHandler: @Valid 입력 검증 실패 → 필드명 포함 메시지

- [x] 통과 (2026-05-31, 근거: 단위테스트 PASS; MethodArgumentNotValidException/ConstraintViolationException/MissingServletRequestParameterException/MethodArgumentTypeMismatchException 모두 HTTP 400 + INVALID_INPUT)
- **관련 REQ**: 해당 없음
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경
- **실행 단계**:
  1. `GlobalExceptionHandlerTest` "MethodArgumentNotValidException" 케이스 확인
  2. "ConstraintViolationException" 케이스 확인
  3. "MissingServletRequestParameterException" 케이스 확인
  4. "MethodArgumentTypeMismatchException" 케이스 확인
- **기대 결과**: 모두 HTTP 400; `code == "INVALID_INPUT"`; message에 필드명(`email`, `page`, `scheduleId` 등) 포함
- **검증 포인트**: `response.body!!.code shouldBe "INVALID_INPUT"`; `response.body!!.message shouldBe "email: 이메일 형식이 아닙니다"` (FieldError 포맷); 필수 파라미터 누락 시 `"필수 파라미터 'page'이(가) 누락되었습니다."` 형식

---

### TC-SEC-015 — GlobalExceptionHandler: 미처리 Exception → 500 내부 오류, 세부 정보 미노출

- [x] 통과 (2026-05-31, 근거: 단위테스트 PASS; RuntimeException('예기치 못한 오류') → HTTP 500 + INTERNAL_SERVER_ERROR + 원본 메시지 미노출)
- **관련 REQ**: 해당 없음
- **분류**: 보안 | 예외
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경
- **실행 단계**:
  1. `GlobalExceptionHandlerTest` "Exception (fallback) - INTERNAL_SERVER_ERROR status를 반환한다" 케이스 확인
  2. `RuntimeException("예기치 못한 오류")` 발생 시 응답 body에 스택 트레이스/원인 메시지 미포함 확인
  3. `response.body!!.message shouldBe ErrorCode.INTERNAL_SERVER_ERROR.message` (고정 문자열) 확인
- **기대 결과**: HTTP 500; `code == "INTERNAL_SERVER_ERROR"`; `message`는 `"서버 내부 오류가 발생했습니다."` 고정 (원본 예외 메시지 미노출)
- **검증 포인트**: `response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR`; body에 `"예기치 못한 오류"` 문자열이 없음 (정보 노출 방지)

---

### TC-SEC-016 — FeignErrorDecoder: 4xx/5xx 상태 코드별 예외 변환 계약

- [x] 통과 (2026-05-31, 근거: 단위테스트 10개 케이스 PASS; 401→INTERNAL_API_UNAUTHORIZED, 404→RESOURCE_NOT_FOUND, 400/403→INVALID_INPUT, 500/503→RetryableException)
- **관련 REQ**: REQ-INT-005
- **분류**: 예외
- **우선순위**: P0(필수/핵심)
- **사전조건**: 단위 테스트 환경 (`FeignErrorDecoderTest`)
- **실행 단계**:
  1. `./gradlew :common-web:test --tests "com.ticketqueue.common.config.FeignErrorDecoderTest"`
  2. 401 응답 → `BusinessException(INTERNAL_API_UNAUTHORIZED)` 확인
  3. 404 응답 → `BusinessException(RESOURCE_NOT_FOUND)` 확인
  4. 400/403 응답 → `BusinessException(INVALID_INPUT)` 확인
  5. 500/503 응답 → `RetryableException` 확인 (재시도 가능)
- **기대 결과**: 4xx는 `BusinessException`, 5xx는 `RetryableException`; 응답 body 파싱 성공 시 body의 `message` 필드 사용; 파싱 실패/body 없음 시 `ErrorCode` 기본 message 사용
- **검증 포인트**: `ex.shouldBeInstanceOf<BusinessException>()`, `ex.errorCode shouldBe ErrorCode.INTERNAL_API_UNAUTHORIZED`; 5xx: `ex.shouldBeInstanceOf<RetryableException>()`, `ex.status() shouldBe 500`

---

### TC-SEC-017 — PortoneTokenService: 토큰 캐싱 및 만료 2분 전 자동 갱신 (Double-Check 동시성)

- [x] 통과 (2026-05-31, 근거: 신규 동시성 테스트 추가 및 PASS; 동시 10 스레드에서 login 정확히 1회 호출; 캐싱·토큰 갱신 케이스 모두 통과)
- **관련 REQ**: 해당 없음
- **분류**: 동시성
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경 (`PortoneTokenServiceTest`), MockK로 `Instant.now()` stub
- **실행 단계**:
  1. `./gradlew :common-web:test --tests "com.ticketqueue.common.external.portone.PortoneTokenServiceTest"`
  2. "유효한 토큰이 있으면 기존 토큰을 반환한다" — 10분 후 2번째 호출 시 login 1회만 호출됨 확인
  3. "토큰 로테이션 테스트" — 만료 60초 전으로 시간 이동 후 refreshToken 호출 확인
  4. 동시성 검증: 동일 `PortoneTokenService` 인스턴스에 대해 10개 스레드 동시 `getAccessToken()` 호출, login이 1회만 실행되는지 확인
  5. ```kotlin
     val service = portoneTokenService
     val results = (1..10).map {
         Thread { service.getAccessToken() }.also { it.start() }
     }
     results.forEach { it.join() }
     verify(exactly = 1) { portoneClient.login(any()) }
     ```
- **기대 결과**: 캐시 히트 시 login 재호출 없음; 만료 직전(expiryTime - 120s) 자동 갱신; 동시 10 스레드에서 login 호출은 정확히 1회 (synchronized + double-check 동작)
- **검증 포인트**: `verify(exactly = 1) { portoneClient.login(any()) }`; 토큰 값이 "Bearer {accessToken}" 형식

---

### TC-SEC-018 — PortoneFallbackFactory: CallNotPermittedException → PortoneCircuitOpenException, 일반 예외는 원본 재전파

- [x] 통과 (2026-05-31, 근거: 단위테스트 4개 케이스 PASS; CallNotPermittedException → PortoneCircuitOpenException(cause=원본); RuntimeException → 원본 재전파)
- **관련 REQ**: 해당 없음
- **분류**: 예외 | 보상트랜잭션
- **우선순위**: P1(중요)
- **사전조건**: 단위 테스트 환경 (`PortoneFallbackFactoryTest`), Resilience4j `CircuitBreaker.ofDefaults("portone-test")`
- **실행 단계**:
  1. `./gradlew :common-web:test --tests "com.ticketqueue.common.external.portone.PortoneFallbackFactoryTest"`
  2. "preRegisterMapsCircuitOpen" — CB OPEN 상태에서 `PortoneCircuitOpenException` 발생, `cause` 가 원본 `CallNotPermittedException` 인지 확인
  3. "getPaymentMapsCircuitOpen", "loginMapsCircuitOpen" 동일 매핑 확인
  4. "rethrowsRuntimeCauseAsIs" — 일반 `RuntimeException` cause는 `PortoneCircuitOpenException` 으로 감싸지지 않고 원본 그대로 재전파 확인
  5. `PortoneCircuitOpenException` 이 `BusinessException(ErrorCode.PORTONE_CIRCUIT_OPEN)` 을 상속하여 503 응답으로 이어짐 확인
- **기대 결과**: `CallNotPermittedException` → `PortoneCircuitOpenException(cause=원본)`; 다른 예외 → 원본 그대로; `ErrorCode.PORTONE_CIRCUIT_OPEN.status == HttpStatus.SERVICE_UNAVAILABLE`
- **검증 포인트**: `shouldThrow<PortoneCircuitOpenException> { ... }`, `ex.cause shouldBe cause`; `shouldThrow<RuntimeException>` 재전파; `PortoneCircuitOpenException`의 `errorCode` 가 `PORTONE_CIRCUIT_OPEN` 임을 `GlobalExceptionHandler` 통합 경로에서 503 응답 확인
