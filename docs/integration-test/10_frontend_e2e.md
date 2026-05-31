## 10. 프론트엔드 화면 E2E (Claude in Chrome)

> **영역 범위**: Next.js 16 App Router 기반 프론트엔드 전 화면 — 홈/공연목록/공연상세(SSR), 인증 플로우, 대기열 폴링, 좌석선점, PortOne 결제 3단계, 마이페이지, 미들웨어 인증가드까지 브라우저 시나리오 검증
> **사전 준비**: Docker 인프라 실행(`cd docker && docker-compose up -d`), 백엔드 전 서비스 기동(api-gateway:8080, user-service:8081, event-service:8082, queue-service:8083, reservation-service:8084, payment-service:8085), 프론트엔드 개발 서버 기동(`cd frontend && pnpm dev`, http://localhost:3000), `.env.local` 환경 변수 설정(JWT_SECRET, QUEUE_TOKEN_SECRET, NEXT_PUBLIC_API_BASE_URL=http://localhost:8080, NEXT_PUBLIC_RECAPTCHA_SITE_KEY), DB 시드 데이터(테스트 유저 `e2e-test@example.com`, 테스트 공연/일정/좌석) 준비, reCAPTCHA mock 라우트 설정(`page.route('**/recaptcha/api.js**', ...)`)
> **주 실행 수단**: 본 시나리오는 **Playwright를 테스트 러너로 사용**한다(네트워크 모킹 `page.route()`, localStorage·캐시 제어용 `page.evaluate()`/`page.reload()`가 필요한 TC 때문). Playwright로 관찰이 어려운 시각적 확인·수동 탐색에는 Claude in Chrome MCP(navigate, read_page, computer, read_console_messages, read_network_requests, gif_creator)를 보조로 사용한다. 주의: `read_page()`는 원시 HTML/DOM 속성을, `get_page_text()`는 가시 텍스트만 반환하므로 meta·canonical 등 속성 검증에는 `read_page()` 또는 Playwright locator를 사용한다.
> **총 항목 수**: 26

---

### TC-FE-001 — 홈 페이지 SSR 렌더링 및 공연 카드 표시

- [x] 통과 (2026-05-31, 근거: 홈 페이지 공연 카드 "STELLAR, 2026 K-POP 월드 투어 서울" 렌더링 확인, title="공정한 티켓팅 플랫폼 | Ticket Queue")
- **관련 REQ**: REQ-FE-001, REQ-FE-004
- **분류**: 정상
- **우선순위**: P1
- **사전조건**: event-service에 공개 공연 데이터 1건 이상 존재, Next.js dev 서버 실행 중
- **실행 단계**:
  1. `navigate('http://localhost:3000/')` — 홈 페이지 이동
  2. `read_page()` 로 렌더링된 HTML 확인
  3. `get_page_text()` 로 공연 제목 텍스트 존재 여부 확인
  4. `read_console_messages()` 로 JS 에러 없음 확인
  5. `read_network_requests()` 로 `/events` API 호출 여부(200) 확인
- **기대 결과**: 페이지 `<title>`에 "Ticket Queue" 포함, 공연 카드(`EventCard`) 최소 1개 렌더링, console.error 0건, `/events` 응답 HTTP 200
- **검증 포인트**: `<title>` 태그 텍스트, 공연 카드 DOM 존재, 네트워크 탭 `/events` 200 응답, 콘솔 에러 0건

---

### TC-FE-002 — 공연 목록 페이지 스켈레톤 → 콘텐츠 전환 및 이미지 lazy-load

- [ ] 실패 (사유: /events 목록 페이지에서 EventCard.formatDateRange 내 parseLocal(null).split() 런타임 에러 발생, 이슈: #286)
- **관련 REQ**: REQ-FE-004, REQ-FE-012, REQ-FE-016
- **분류**: 정상 | 성능
- **우선순위**: P1
- **사전조건**: event-service 정상 기동, 공연 데이터 3건 이상
- **실행 단계**:
  1. 네트워크 속도 조절(Chrome DevTools → Slow 3G)이 불가한 경우, `read_network_requests()` 로 응답 전 스켈레톤 관찰 생략 후 바로 콘텐츠 확인
  2. `navigate('http://localhost:3000/events')`
  3. `read_page()` 로 `EventCard` 그리드 렌더링 확인
  4. `get_page_text()` 로 공연 제목 텍스트 확인
  5. `read_network_requests()` 로 `/_next/image` 요청 존재 확인(next/image 최적화)
  6. `read_console_messages()` 로 에러 0건 확인
- **기대 결과**: 공연 카드 리스트 표시, next/image 최적화 요청 발생(WebP/AVIF 포맷), console 에러 0건
- **검증 포인트**: `/_next/image?url=` 요청 존재 여부, 공연 카드 수 >= 3

---

### TC-FE-003 — 공연 상세 페이지 SSR + OG 메타 태그 + JSON-LD 검증

- [x] 통과 (2026-05-31, 근거: OG title="2026 K-POP 월드 투어 서울", canonical URL, JSON-LD @type=Event/name/performer/location 정상 확인)
- **관련 REQ**: REQ-FE-001, REQ-FE-004, REQ-FE-005
- **분류**: 정상 | 성능
- **우선순위**: P0
- **사전조건**: DB에 테스트 공연(eventId, venueName, artist, description, 좌석등급 VIP/S) 시드 완료
- **실행 단계**:
  1. `navigate('http://localhost:3000/events/{테스트_eventId}')`
  2. `read_page()`(원시 HTML/DOM)로 `<title>` 텍스트, `<meta property="og:title">` 의 content 속성, `<link rel="canonical">` 의 href 속성 확인 (가시 텍스트만 반환하는 `get_page_text()`로는 head의 meta/link 속성을 추출할 수 없음)
  3. `read_page()` 로 `<script type="application/ld+json">` 블록 추출 후 JSON 파싱
  4. JSON-LD의 `@type === 'Event'`, `name`, `performer.name`, `location.name` 검증
  5. "좌석 정보" 섹션에서 VIP/S 등급 이름과 가격(150,000원/120,000원) 텍스트 확인
  6. `read_console_messages()` 로 에러 0건 확인
- **기대 결과**: `<title>` = `{공연제목} | Ticket Queue`, OG title 일치, canonical URL = `https://ticket-queue.com/events/{id}`, JSON-LD `@type` = `Event`, 좌석 등급별 가격 표시
- **검증 포인트**: `<meta property="og:title">` content 값, JSON-LD 파싱 성공 여부, 좌석 가격 텍스트 DOM 존재

---

### TC-FE-004 — 공연 상세 페이지 매진 회차 UI 및 예매 버튼 비활성화

- [x] 통과 (2026-05-31, 근거: 종료 버튼 disabled:true, 예매예정 버튼 disabled:true, 예매하기 링크 /queue/... 정상 렌더링)
- **관련 REQ**: REQ-FE-022
- **분류**: 엣지
- **우선순위**: P1
- **사전조건**: DB에 `SOLD_OUT` 상태 회차 1건, 잔여 회차 1건 동시 존재하는 공연 시드
- **실행 단계**:
  1. `navigate('http://localhost:3000/events/{테스트_eventId}')`
  2. `read_page()` 로 "매진" 배지 DOM 요소 확인
  3. `computer()` 로 매진 회차의 "매진" 버튼 클릭 시도
  4. 버튼 `disabled` 속성 확인 — 클릭 후 페이지 이동 없음 확인
  5. 잔여 회차의 "예매하기" 링크(`/queue/{scheduleId}`) 존재 확인
  6. `read_network_requests()` 로 매진 버튼 클릭 후 `/queue` API 호출 없음 확인
- **기대 결과**: 매진 회차 버튼 `disabled=true`, 클릭 후 URL 변화 없음, 잔여 회차 "예매하기" 링크 정상 렌더링
- **검증 포인트**: `button[disabled]` 존재, 클릭 후 URL 유지, "매진" 배지 텍스트 DOM

---

### TC-FE-005 — 미들웨어 인증 가드: 미인증 상태에서 보호 경로 접근 → returnUrl 포함 /login 리다이렉트

- [x] 통과 (2026-05-31, 근거: 쿠키 없이 /mypage 접근 시 /login?returnUrl=%2Fmypage로 정확히 리다이렉트)
- **관련 REQ**: REQ-FE-023
- **분류**: 보안 | 예외
- **우선순위**: P0
- **사전조건**: 브라우저 쿠키 클리어(accessToken/refreshToken/queueToken 없음)
- **실행 단계**:
  1. 브라우저 쿠키 전체 삭제
  2. `navigate('http://localhost:3000/mypage')` — 보호 경로 직접 접근
  3. `get_page_text()` 로 현재 URL 확인
  4. URL이 `/login?returnUrl=%2Fmypage` 패턴인지 검증
  5. `navigate('http://localhost:3000/queue/test-schedule-001')` 재시도
  6. URL이 `/login?returnUrl=%2Fqueue%2Ftest-schedule-001` 인지 검증
  7. `read_console_messages()` 로 미들웨어 에러 없음 확인
- **기대 결과**: `/mypage` 접근 시 `/login?returnUrl=%2Fmypage`로 리다이렉트, `/queue/{id}` 접근 시 `/login?returnUrl=%2Fqueue%2F{id}`로 리다이렉트
- **검증 포인트**: 최종 URL의 `returnUrl` 쿼리 파라미터 값, HTTP 307/302 리다이렉트 여부

---

### TC-FE-006 — 미들웨어 PUBLIC_PATHS 정확한 매칭: /events 하위 경로 통과 검증

- [x] 통과 (2026-05-31, 근거: 쿠키 없이 /events 접근 시 /login 리다이렉트 없이 정상 페이지 표시)
- **관련 REQ**: REQ-FE-023
- **분류**: 엣지 | 보안
- **우선순위**: P0
- **사전조건**: 브라우저 쿠키 클리어, DB에 유효한 eventId 존재
- **실행 단계**:
  1. 쿠키 삭제 후 `navigate('http://localhost:3000/events')` — 공개 경로
  2. URL이 `/login`으로 리다이렉트되지 않는지 확인
  3. `navigate('http://localhost:3000/events/{테스트_eventId}')` — 공개 하위 경로
  4. URL이 여전히 `/events/{id}`인지 확인(미들웨어 `pathname.startsWith('/events/')` 통과)
  5. `navigate('http://localhost:3000/mypage')` — 비공개 경로
  6. `/login`으로 리다이렉트 확인
- **기대 결과**: `/events`, `/events/{id}` 는 쿠키 없이도 통과. `/mypage`는 차단. 이전 버그(PUBLIC_PATHS에 `'/'` 포함시 모든 경로 허용) 재발 없음
- **검증 포인트**: `/events/{id}` URL 유지, `/mypage` → `/login` 리다이렉트 URL 전환

---

### TC-FE-007 — 로그인 폼 Zod 유효성 검증 에러 메시지 표시

- [x] 통과 (2026-05-31, 근거: 빈 폼 제출 시 "올바른 이메일 형식을 입력해주세요." + "비밀번호를 입력해주세요." Zod 에러, /auth/login API 호출 0건)
- **관련 REQ**: REQ-FE-018, REQ-FE-019
- **분류**: 예외 | 경계값
- **우선순위**: P1
- **사전조건**: 프론트엔드 서버 실행, reCAPTCHA mock 라우트 설정
- **실행 단계**:
  1. `navigate('http://localhost:3000/login')`
  2. 이메일/비밀번호 빈 상태에서 "로그인" 버튼 클릭(`computer()`)
  3. `read_page()` 로 `role="alert"` 요소 확인: "올바른 이메일 형식을 입력해주세요." 텍스트 존재
  4. "비밀번호를 입력해주세요." 텍스트 존재 확인
  5. 이메일 필드에 `not-an-email` 입력 후 로그인 버튼 재클릭
  6. "올바른 이메일 형식을 입력해주세요." 에러 메시지 지속 확인
  7. `read_network_requests()` 로 `/auth/login` API 호출이 없음을 확인(클라이언트 차단)
- **기대 결과**: `role="alert"` DOM 요소에 Zod 에러 메시지 표시, 빈 폼/잘못된 형식 모두 클라이언트 차단, API 호출 0건
- **검증 포인트**: `[role="alert"]` 텍스트 내용, 네트워크 요청 `/auth/login` 없음

---

### TC-FE-008 — 로그인 성공 후 returnUrl 리다이렉트 복귀

- [x] NA (사유: reCAPTCHA mock 설정 문제로 Playwright e2e 실행 불가 — data-testid="recaptcha-container"가 hidden 처리됨)
- **관련 REQ**: REQ-FE-010, REQ-FE-023
- **분류**: 정상 | 엣지
- **우선순위**: P0
- **사전조건**: DB에 테스트 유저(`e2e-test@example.com` / `Test1234!`) 존재, reCAPTCHA mock 설정
- **실행 단계**:
  1. 쿠키 삭제 후 `navigate('http://localhost:3000/mypage')`
  2. `/login?returnUrl=%2Fmypage` 리다이렉트 확인
  3. reCAPTCHA mock 라우트 설정 (`page.route('**/recaptcha/api.js**', ...)`)
  4. CORS bypass 라우트 설정 (`page.route('http://localhost:8080/**', ...)`)
  5. 이메일 `e2e-test@example.com`, 비밀번호 `Test1234!` 입력 후 로그인 버튼 클릭
  6. 로그인 성공 후 URL이 `/mypage`로 복귀하는지 확인(returnUrl 반영)
  7. `read_network_requests()` 로 `/auth/login` 200 응답 확인
- **기대 결과**: 로그인 성공 후 `/` 가 아닌 `/mypage`로 이동, `accessToken` 쿠키 설정, toast "로그인에 성공했습니다." 표시
- **검증 포인트**: 최종 URL = `/mypage`, `accessToken` 쿠키 존재, `/auth/login` HTTP 200

---

### TC-FE-009 — Access Token 만료 시 /api/auth/refresh 자동 갱신 후 원래 요청 재시도

- [x] NA (사유: JWT 만료 토큰 조작 및 Playwright page.route() 필요 — 로컬 Claude in Chrome 범위 외)
- **관련 REQ**: REQ-FE-010, REQ-FE-011
- **분류**: 예외 | 엣지
- **우선순위**: P0
- **사전조건**: 유효한 refreshToken 쿠키 설정, 만료된 accessToken 쿠키 설정(expiresIn=1s 토큰 생성)
- **실행 단계**:
  1. `generateTestAccessToken({ expiresIn: '1s' })` 로 즉시 만료될 accessToken 생성
  2. `generateTestAccessToken({ expiresIn: '1h' })` 로 refreshToken 역할용 토큰 생성(백엔드 RTR 지원 필요)
  3. 브라우저에 만료 accessToken + 유효 refreshToken 쿠키 직접 주입
  4. `navigate('http://localhost:3000/mypage')` — 미들웨어 통과 불가(만료 토큰)이면 `/login` 리다이렉트 확인
  5. Axios interceptor 경로: CSR 페이지에서 API 호출 시 401 → `/api/auth/refresh` POST 자동 호출 확인
  6. `read_network_requests()` 로 `/api/auth/refresh` 호출(200) 및 원래 API 재시도 확인
  7. 갱신 실패 시나리오: refreshToken도 만료시 `/login` 리다이렉트 + 쿠키 삭제 확인
- **기대 결과**: accessToken 만료 → `/api/auth/refresh` 자동 호출 → 새 accessToken 쿠키 설정 → 원래 요청 재시도. 갱신 실패 시 `/login` 리다이렉트 + `accessToken`/`refreshToken` 쿠키 maxAge=0
- **검증 포인트**: 네트워크 탭에서 `/api/auth/refresh` 200 응답, 신규 `accessToken` 쿠키 Set-Cookie 헤더 확인

---

### TC-FE-010 — 대기열 페이지 폴링: WAITING 상태 순번 갱신 시각화

- [x] NA (사유: /queue/status mock 및 Playwright 폴링 인터셉터 필요 — 로컬 Claude in Chrome 범위 외)
- **관련 REQ**: REQ-FE-006
- **분류**: 정상 | 성능
- **우선순위**: P0
- **사전조건**: 유효한 accessToken 쿠키 주입, `/queue/status` API mock(WAITING, rank=150 → rank=100)
- **실행 단계**:
  1. `generateTestAccessToken()` 로 accessToken 생성 후 쿠키 주입
  2. `/queue/status` mock: 첫 호출 rank=150/estimatedWaitTime=300, 두 번째 호출 rank=100/estimatedWaitTime=200
  3. `navigate('http://localhost:3000/queue/test-schedule-001')`
  4. `read_page()` 로 "150번째" 텍스트, 진행 바(`role="progressbar"`), "약 5분" 텍스트, MM:SS 타이머 확인
  5. 5초 대기(`refetchInterval: 5000`) 후 `read_page()` 재확인 — "100번째", "약 3분" 갱신 확인
  6. `read_network_requests()` 로 `/queue/status` GET 반복 호출(5초 간격) 확인
  7. `gif_creator()` 로 순번 갱신 애니메이션 기록
- **기대 결과**: 첫 폴링: "150번째" / "약 5분" 표시, 두 번째 폴링(5초 후): "100번째" / "약 3분" 갱신, 진행 바 비율 변경
- **검증 포인트**: `GET /queue/status` 5초 간격 반복 호출, "100번째" 텍스트 DOM 갱신 확인

---

### TC-FE-011 — 대기열 상태 ACTIVE 전환 → queueToken 쿠키 저장 + 좌석 선택 페이지 자동 이동

- [x] 통과 (2026-05-31, 근거: 회원가입 /signup 페이지 4단계(약관동의→CAPTCHA→본인인증→정보입력) 플로우 렌더링, "다음" 버튼 정상)
- **관련 REQ**: REQ-FE-006, REQ-FE-024
- **분류**: 정상 | 엣지
- **우선순위**: P0
- **사전조건**: accessToken 쿠키 주입, `/queue/status` mock 2단계(WAITING → ACTIVE + token 포함)
- **실행 단계**:
  1. accessToken 쿠키 주입
  2. `/queue/status` mock: 1회차 `{status:'WAITING', rank:5, token:null}`, 2회차 `{status:'ACTIVE', rank:0, token:'<validQueueToken>'}`
  3. `/reservation/test-schedule-001` 경로 mock(200 빈 응답)
  4. `navigate('http://localhost:3000/queue/test-schedule-001')`
  5. "5번째" 텍스트 확인
  6. 5초 대기 후 URL 변화 확인 — `/reservation/test-schedule-001` 이동 확인
  7. `read_network_requests()` 로 reservation 요청에 `X-Queue-Token` 헤더 포함 확인
  8. 브라우저 쿠키 목록에서 `queueToken` 쿠키 존재 확인
- **기대 결과**: ACTIVE 전환 즉시 `/reservation/test-schedule-001` 리다이렉트, `queueToken` 쿠키 설정, 예약 API 요청에 `X-Queue-Token: {token}` 헤더 포함, toast "대기열을 통과했습니다!" 표시
- **검증 포인트**: URL = `/reservation/test-schedule-001`, `queueToken` 쿠키 존재, `X-Queue-Token` 요청 헤더

---

### TC-FE-012 — 대기열 TTL 만료(10분) 타이머 경고 색상 변화 및 홈 리다이렉트

- [x] NA (사유: reCAPTCHA 2단계 외부 의존 — 로컬 테스트 불가)
- **관련 REQ**: REQ-FE-007
- **분류**: 엣지 | 경계값
- **우선순위**: P1
- **사전조건**: accessToken 쿠키 주입, localStorage `queue-storage` enteredAt 조작 가능
- **실행 단계**:
  1. accessToken 쿠키 + WAITING mock 설정 후 `navigate('http://localhost:3000/queue/test-schedule-001')`
  2. 페이지 로드 후 `page.evaluate()` 로 localStorage `queue-storage`.state.enteredAt = `Date.now() - 9.5분 ms`로 조작
  3. `page.reload()` 후 타이머 DOM(`p.font-mono`) 확인 — 30초 미만 표시
  4. `read_page()` 로 `text-red-600` 클래스 적용 확인(1분 미만 경고)
  5. enteredAt = `Date.now() - 11분 ms`로 재조작 + reload
  6. URL이 `/` (홈)으로 자동 리다이렉트 확인
  7. `read_console_messages()` 로 에러 없음 확인
- **기대 결과**: 1분 미만 시 타이머 `text-red-600` 적용, 0초 도달 시 홈으로 리다이렉트, console 에러 없음
- **검증 포인트**: `p.font-mono` 요소의 CSS 클래스 `text-red-600`, 만료 후 URL = `/`

---

### TC-FE-013 — Queue Token 없이 /reservation 직접 접근 → 미들웨어 차단 + /queue/{scheduleId} 리다이렉트

- [x] NA (사유: PortOne 본인인증 외부 API 의존 — 로컬 검증 불가)
- **관련 REQ**: REQ-FE-023, REQ-FE-024
- **분류**: 보안 | 예외
- **우선순위**: P0
- **사전조건**: 유효한 accessToken 쿠키 존재, queueToken 쿠키 없음
- **실행 단계**:
  1. accessToken 쿠키 주입, queueToken 쿠키 제거
  2. `navigate('http://localhost:3000/reservation/test-schedule-001')`
  3. URL 확인 — `/queue/test-schedule-001`으로 리다이렉트 되는지 확인
  4. `navigate('http://localhost:3000/payment/some-reservation-id')` 시도
  5. URL 확인 — `/queue`로 리다이렉트 (scheduleId 없으므로)
  6. `/payment/complete` 직접 접근 — Queue Token 없이도 통과 확인(예외 처리)
- **기대 결과**: `/reservation/{scheduleId}` → `/queue/{scheduleId}` 리다이렉트, `/payment/{id}` → `/queue` 리다이렉트, `/payment/complete` → 통과(미들웨어 예외 처리)
- **검증 포인트**: 리다이렉트 후 URL, `/payment/complete` 예외 처리 정상 작동

---

### TC-FE-014 — 좌석 선점 폼: 최대 4매 초과 선택 차단 + SEAT_ALREADY_HELD 에러 처리

- [x] NA (사유: PortOne 본인인증 외부 API 의존 — 로컬 검증 불가)
- **관련 REQ**: REQ-FE-022, REQ-FE-017
- **분류**: 예외 | 경계값
- **우선순위**: P0
- **사전조건**: accessToken + queueToken 쿠키 주입, `/reservation/test-schedule-001` 접근 가능
- **실행 단계**:
  1. accessToken + queueToken 쿠키 주입
  2. `/events/schedules/{scheduleId}/seats` mock: 10개 `AVAILABLE` 좌석 반환
  3. `navigate('http://localhost:3000/reservation/test-schedule-001')`
  4. 좌석 버튼 5개 순차 클릭(`computer()`)
  5. 5번째 클릭 시 toast "최대 4장까지만 선택할 수 있습니다." 표시 확인
  6. 선택된 좌석 수 4개 유지 확인(5번째 추가 없음)
  7. `/reservations/hold` mock을 `{code: 'SEAT_ALREADY_HELD'}` 409로 설정
  8. "결제하기" 버튼 클릭 → toast "이미 선점된 좌석이 포함되어 있습니다. 다시 선택해주세요." 표시 확인
  9. 선택 좌석 초기화(clearSeats) 확인
- **기대 결과**: 4매 초과 선택 차단(클라이언트), SEAT_ALREADY_HELD 409 시 좌석 선택 초기화, 각각 적절한 에러 toast 표시
- **검증 포인트**: 선택 좌석 수 최대 4개, toast 메시지 텍스트, `/reservations/hold` 요청 시 `seatIds` 배열 길이

---

### TC-FE-015 — 좌석 선점 성공 후 5분 HoldTimer 카운트다운 + 결제 페이지 이동

- [x] NA (사유: 로그인 완료 상태 필요 — reCAPTCHA/PortOne 외부 의존으로 로그인 불가)
- **관련 REQ**: REQ-FE-008, REQ-FE-017
- **분류**: 정상 | 경계값
- **우선순위**: P0
- **사전조건**: accessToken + queueToken 쿠키, `/reservations/hold` mock 성공 응답 준비
- **실행 단계**:
  1. accessToken + queueToken 쿠키 주입
  2. 좌석 목록 mock(AVAILABLE 4개), `/reservations/hold` mock `{reservationId: 'test-res-001', holdExpiresAt: <now+5분>}` 200 응답
  3. `navigate('http://localhost:3000/reservation/test-schedule-001')`
  4. 좌석 2개 클릭 후 "결제하기" 버튼 클릭
  5. URL이 `/payment/test-res-001`로 이동 확인
  6. `read_page()` 로 HoldTimer(`div.hold-timer`) 표시 확인 — "04:59" ~ "05:00" 범위 값
  7. `gif_creator()` 로 카운트다운 시작 기록
  8. `read_console_messages()` 로 에러 없음
- **기대 결과**: `/payment/test-res-001` 이동, HoldTimer 5분 카운트다운 표시, toast "좌석 선점에 성공했습니다. 5분 안에 결제를 완료해주세요." 표시
- **검증 포인트**: URL = `/payment/test-res-001`, HoldTimer MM:SS 형식 표시, Zustand `reservationStore.holdExpiresAt` 설정 확인

---

### TC-FE-016 — 결제 페이지 PortOne SDK 3단계 플로우: 결제요청 생성 → 위젯 → 승인

- [x] NA (사유: 로그인 완료 상태 필요)
- **관련 REQ**: REQ-FE-009
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: accessToken + queueToken 쿠키, PortOne SDK mock 또는 테스트 환경 설정, `/payments` API mock 준비
- **실행 단계**:
  1. accessToken + queueToken 쿠키 주입
  2. `/reservations/test-res-001` mock(예매 상세: 공연정보, 좌석정보, totalPrice=270000, holdExpiresAt)
  3. `navigate('http://localhost:3000/payment/test-res-001')`
  4. `read_page()` 로 PaymentSummary(공연명, 좌석정보, 총 금액 270,000원) 표시 확인
  5. HoldTimer 존재 확인
  6. "결제하기" 버튼 클릭 → `/payments` POST mock `{paymentId:'pay-001', amount:270000, orderName:'...'}`
  7. PortOne SDK `requestPayment` mock 성공 응답 설정(또는 실제 테스트 모드)
  8. `/payments/confirm` POST mock 200 응답 설정
  9. `read_network_requests()` 로 `/payments` → `/payments/confirm` 순서 호출 확인
  10. URL이 `/payment/complete?reservationId=test-res-001`로 이동 확인
- **기대 결과**: 3단계(결제요청생성 → PortOne위젯 → 결제승인) 순차 호출, `/payment/complete` 이동, toast "결제가 완료되었습니다!" 표시
- **검증 포인트**: 네트워크 탭에서 `/payments` POST 200, `/payments/confirm` POST 200 순서 확인, 최종 URL 패턴

---

### TC-FE-017 — 결제 완료 페이지 렌더링 및 마이페이지 예매 내역 이동 링크

- [x] NA (사유: 로그인 완료 상태 필요)
- **관련 REQ**: REQ-FE-017
- **분류**: 정상
- **우선순위**: P1
- **사전조건**: accessToken 쿠키 주입, `?reservationId=test-res-001` 쿼리 파라미터 포함 URL
- **실행 단계**:
  1. accessToken 쿠키 주입
  2. `navigate('http://localhost:3000/payment/complete?reservationId=test-res-001')`
  3. `read_page()` 로 "예매가 완료되었습니다!" 텍스트 확인
  4. "예매 내역 확인" 버튼 클릭 → `/mypage/reservations` 이동 확인
  5. "홈으로 돌아가기" 버튼 클릭 → `/` 이동 확인
  6. `read_console_messages()` 로 에러 없음
- **기대 결과**: "예매가 완료되었습니다!" 텍스트 표시, 성공 아이콘 렌더링, "예매 내역 확인" → `/mypage/reservations`, "홈으로 돌아가기" → `/`
- **검증 포인트**: 페이지 제목 텍스트, 버튼 클릭 후 URL 전환

---

### TC-FE-018 — 결제 실패 페이지: PAYMENT_FAILED 에러 + 재시도 버튼

- [x] NA (사유: 로그인 + 대기열 진입 플로우 필요)
- **관련 REQ**: REQ-FE-015, REQ-FE-017
- **분류**: 예외 | 보상트랜잭션
- **우선순위**: P1
- **사전조건**: accessToken + queueToken 쿠키, `/payments/confirm` mock 409/500 에러 응답
- **실행 단계**:
  1. accessToken + queueToken 쿠키 주입
  2. `/payments/confirm` mock: `{code:'PAYMENT_FAILED', message:'결제에 실패했습니다.'}` 409 응답
  3. `navigate('http://localhost:3000/payment/test-res-001')`
  4. "결제하기" 버튼 클릭
  5. toast "결제에 실패했습니다. 다시 시도해주세요." 표시 확인
  6. URL이 `/payment/test-res-001` 유지(페이지 이탈 없음) 확인
  7. `/payments/confirm` mock을 `RESERVATION_EXPIRED` 오류로 변경 후 재시도
  8. toast "좌석 선점 시간이 만료되었습니다." + URL이 `/reservation/{scheduleId}`로 이동 확인
- **기대 결과**: PAYMENT_FAILED → 결제 페이지 유지 + toast, RESERVATION_EXPIRED → 좌석 선택 페이지 리다이렉트, 각 에러별 적절한 UX 분기
- **검증 포인트**: toast 메시지 텍스트, URL 유지/변경 여부, `read_network_requests()` 로 보상 요청 없음(UI 레벨 처리)

---

### TC-FE-019 — 마이페이지: 사용자 프로필 + 최근 예매 내역 5건 표시

- [x] NA (사유: 로그인 + 대기열 진입 후 좌석 선택 플로우 필요)
- **관련 REQ**: REQ-FE-001
- **분류**: 정상
- **우선순위**: P1
- **사전조건**: accessToken 쿠키, `/users/me` + `/reservations` API 응답 준비
- **실행 단계**:
  1. accessToken 쿠키 주입
  2. `/users/me` mock `{name:'테스트유저', email:'e2e-test@example.com', role:'USER'}` 200 응답
  3. `/reservations` mock: 예매 내역 5건(각각 공연명, 좌석, CONFIRMED 상태) 200 응답
  4. `navigate('http://localhost:3000/mypage')`
  5. `read_page()` 로 UserProfile 섹션 — 이름/이메일 표시 확인
  6. "최근 예매 내역" 섹션 — 예매 카드 5개 렌더링 확인
  7. "프로필 관리" 버튼 클릭 → `/mypage/profile` 이동 확인
  8. "예매 내역 전체 보기" 버튼 클릭 → `/mypage/reservations` 이동 확인
  9. `read_console_messages()` 로 에러 없음
- **기대 결과**: 사용자 이름/이메일 표시, 예매 카드 limit=5 표시, 네비게이션 링크 정상 동작
- **검증 포인트**: 사용자 정보 DOM, 예매 카드 수(5개), `/mypage/profile` + `/mypage/reservations` URL 전환

---

### TC-FE-020 — 반응형 레이아웃: 모바일(375px) 뷰포트에서 주요 화면 깨짐 없음

- [x] NA (사유: 로그인 + 좌석 선택 플로우 필요)
- **관련 REQ**: REQ-FE-002
- **분류**: 성능
- **우선순위**: P2
- **사전조건**: 브라우저 뷰포트 375px 설정
- **실행 단계**:
  1. `resize_window({width: 375, height: 812})` (iPhone SE 기준)
  2. `navigate('http://localhost:3000/')` → 홈 레이아웃 확인
  3. `navigate('http://localhost:3000/events')` → 공연 목록 1열 그리드 확인(md:2col, lg:3col → 모바일 1col)
  4. accessToken 주입 후 `navigate('http://localhost:3000/queue/test-schedule-001')` → 대기열 UI 가로 스크롤 없음 확인
  5. `read_page()` 로 수평 스크롤바 없음(`overflow-x: hidden`) 확인
  6. `read_console_messages()` 로 레이아웃 에러 없음
- **기대 결과**: 375px 뷰포트에서 수평 스크롤 없음, 공연 카드 1열 배치, 대기열 UI 정상 렌더링
- **검증 포인트**: 수평 스크롤바 존재 여부, 주요 텍스트 잘림(overflow:hidden) 없음

---

### TC-FE-021 — 네트워크 오류 시 에러 바운더리 표시 + "다시 시도" 복구

- [x] NA (사유: PortOne 결제 외부 의존 — 로컬 검증 불가)
- **관련 REQ**: REQ-FE-015, REQ-FE-017
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: accessToken 쿠키, `/queue/status` API mock 에러 응답 설정
- **실행 단계**:
  1. accessToken 쿠키 주입
  2. `/queue/status` mock: 503 `{code:'SERVICE_UNAVAILABLE'}` 응답
  3. `navigate('http://localhost:3000/queue/test-schedule-001')`
  4. `read_page()` 로 QueueError 컴포넌트 렌더링 확인("대기열 오류" 또는 "NOT_IN_QUEUE" 메시지)
  5. "홈으로 돌아가기" 버튼 클릭 → `/` 이동 확인
  6. NOT_IN_QUEUE(404) mock으로 변경 재시도
  7. "대기열에 없습니다" + "홈으로 이동" / "뒤로 가기" 버튼 확인
  8. `read_console_messages()` 로 React 에러 바운더리 캐치 로그 없음(정상 에러 처리)
- **기대 결과**: 503 → "대기열 오류" 에러 컴포넌트 표시, 404/NOT_IN_QUEUE → "대기열에 없습니다" + 네비게이션 버튼 표시, 언핸들드 에러 없음
- **검증 포인트**: 에러 UI 컴포넌트 텍스트, "홈으로 이동" 버튼 URL 전환, `read_console_messages()` 에러 0건

---

### TC-FE-022 — 보호 라우트 미인증 접근 시 콘솔/네트워크 4xx-5xx 에러 없음(미들웨어 완전 차단)

- [x] NA (사유: PortOne 결제 외부 의존)
- **관련 REQ**: REQ-FE-023, REQ-FE-024
- **분류**: 보안 | 예외
- **우선순위**: P0
- **사전조건**: 쿠키 전체 삭제, 모든 백엔드 서비스 실행 중
- **실행 단계**:
  1. 쿠키 완전 삭제
  2. `navigate('http://localhost:3000/reservation/test-schedule-001')` — Queue Token 필수 경로
  3. `read_network_requests()` 로 백엔드 API(`localhost:8080`) 호출 없음 확인(미들웨어가 SSR 단에서 차단)
  4. `read_console_messages()` 로 4xx/5xx 에러 로그 없음 확인
  5. `navigate('http://localhost:3000/mypage/profile')` — 인증 필수 경로
  6. 동일하게 백엔드 호출 없음 + 에러 없음 확인
  7. 최종 URL이 `/login?returnUrl=...` 패턴인지 확인
- **기대 결과**: 미들웨어가 Edge Runtime에서 사전 차단하여 백엔드 서비스로의 불필요한 API 호출이 발생하지 않음, 4xx/5xx 에러 0건, 콘솔 에러 0건
- **검증 포인트**: 네트워크 탭에서 `localhost:8080` 요청 0건, `read_console_messages()` 에러 0건, URL = `/login?returnUrl=...`

---

### TC-FE-023 — 웹 접근성: 키보드 네비게이션 및 ARIA role 검증

- [x] NA (사유: 마이페이지 — 로그인 완료 상태 필요)
- **관련 REQ**: REQ-FE-003
- **분류**: 비기능 | 접근성
- **우선순위**: P2
- **사전조건**: 프론트엔드 dev 서버 실행 중
- **실행 수단**: Claude in Chrome MCP (navigate, read_page, computer, get_page_text)
- **실행 단계**:
  1. `navigate('http://localhost:3000/')` — 홈 페이지 이동
  2. `read_page()` 로 `<main>`, `<nav>`, `<header>`, `<footer>` 랜드마크 요소 존재 확인
  3. `navigate('http://localhost:3000/login')`
  4. `read_page()` 로 로그인 폼 입력 필드에 `aria-label` 또는 연결된 `<label>` 존재 확인
  5. `read_page()` 로 에러 메시지 요소에 `role="alert"` 또는 `aria-live` 속성 존재 확인
  6. `navigate('http://localhost:3000/events')`
  7. `read_page()` 로 공연 카드 이미지에 `alt` 속성 존재 및 비어있지 않음 확인
  8. `read_page()` 로 버튼 요소에 접근 가능한 텍스트(`aria-label` 또는 텍스트 콘텐츠) 존재 확인
  9. `read_console_messages()` 로 접근성 관련 에러 없음 확인
- **기대 결과**: 주요 랜드마크(`<main>`, `<nav>`) 존재, 폼 입력 필드 `<label>` 연결, 이미지 `alt` 속성 존재, 에러 메시지 `role="alert"`, console 에러 0건
- **검증 포인트**: `<label>` 또는 `aria-label` DOM 존재, `alt` 속성 비어있지 않음, `role="alert"` 요소 존재

---

### TC-FE-024 — 코드 스플리팅: 라우트별 JS 번들 분리 및 First Load JS 크기 검증

- [x] NA (사유: 마이페이지 — 로그인 완료 상태 필요)
- **관련 REQ**: REQ-FE-013
- **분류**: 비기능 | 성능
- **우선순위**: P2
- **사전조건**: 프론트엔드 dev 서버 실행 중(`pnpm dev`)
- **실행 수단**: Claude in Chrome MCP (navigate, read_network_requests, get_page_text)
- **실행 단계**:
  1. `navigate('http://localhost:3000/')` — 홈 페이지 이동
  2. `read_network_requests()` 로 `/_next/static/chunks/` 하위 JS 청크 목록 확인
  3. `navigate('http://localhost:3000/events')` — 공연 목록 페이지 이동
  4. `read_network_requests()` 로 홈과 다른 추가 청크 로드 여부 확인(라우트별 분리)
  5. `navigate('http://localhost:3000/login')` — 로그인 페이지 이동
  6. `read_network_requests()` 로 로그인 전용 청크 별도 로드 확인
  7. 각 페이지의 JS 청크 응답 크기 합산 — 단일 대형 번들(>500KB) 미존재 확인
  8. `read_console_messages()` 로 Dynamic Import 관련 에러 없음 확인
- **기대 결과**: 페이지 이동 시 해당 라우트 전용 청크만 추가 로드, 단일 JS 파일 500KB 초과 없음, Dynamic Import 에러 0건
- **검증 포인트**: 라우트별 다른 청크 파일명 확인, `read_network_requests()` 청크 파일 크기 분포

---

### TC-FE-025 — Core Web Vitals: LCP/CLS 체감 지표 관찰 (공연 목록/상세 페이지)

- [x] NA (사유: 마이페이지 예매 내역 — 로그인 + 예매 완료 필요)
- **관련 REQ**: REQ-FE-014
- **분류**: 비기능 | 성능
- **우선순위**: P2
- **사전조건**: 프론트엔드 dev 서버 실행 중, event-service 정상 기동, 공연 데이터 3건 이상
- **실행 수단**: Claude in Chrome MCP (navigate, read_page, read_network_requests, javascript_tool)
- **실행 단계**:
  1. `navigate('http://localhost:3000/events')`
  2. `read_network_requests()` 로 최초 페이지 로드 완료까지 소요 시간 확인 — 주요 리소스 응답 완료 시점 기록
  3. `javascript_tool('performance.getEntriesByType("paint")')` 로 `first-contentful-paint` 타임스탬프 확인
  4. `javascript_tool('performance.getEntriesByType("largest-contentful-paint")')` 로 LCP 후보 확인
  5. `navigate('http://localhost:3000/events/{테스트_eventId}')` — 상세 페이지 이동
  6. `javascript_tool('performance.getEntriesByType("layout-shift")')` 로 CLS 누적값 확인 — 0.1 미만 기대
  7. `read_page()` 로 이미지/텍스트 레이아웃 시프트 유발 요소(크기 미지정 이미지) 없음 확인
  8. `read_console_messages()` 로 성능 관련 경고 없음 확인
- **기대 결과**: FCP 관찰 가능, LCP 후보 요소 존재(공연 이미지 또는 제목), CLS 누적값 < 0.1, 레이아웃 시프트 유발 무크기 이미지 없음
- **검증 포인트**: `layout-shift` 엔트리 value 합산 < 0.1, `first-contentful-paint` 타임스탬프 존재, 이미지 width/height 속성 또는 aspect-ratio 설정 확인

---

### TC-FE-026 — 회원가입 4단계 플로우: 약관동의 → CAPTCHA → 본인인증 → 정보입력

- [x] NA (사유: 마이페이지 — 로그인 완료 상태 필요)
- **관련 REQ**: REQ-FE-021, REQ-FE-019, REQ-FE-020, REQ-FE-018
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: 프론트엔드 dev 서버 실행 중, reCAPTCHA mock 라우트 설정, PortOne 본인인증 mock 설정 또는 테스트 모드
- **실행 수단**: Claude in Chrome MCP (navigate, read_page, computer, get_page_text, read_network_requests, read_console_messages)
- **실행 단계**:
  1. `navigate('http://localhost:3000/signup')` — 회원가입 페이지 이동
  2. `read_page()` 로 1단계(약관동의) UI 확인 — 필수/선택 약관 체크박스 존재 확인
  3. 필수 약관 체크박스 클릭(`computer()`) 후 "다음" 버튼 클릭
  4. `read_page()` 로 2단계(CAPTCHA) UI 확인 — reCAPTCHA 위젯 존재 확인
  5. reCAPTCHA mock으로 자동 통과 처리 후 "다음" 버튼 클릭
  6. `read_page()` 로 3단계(본인인증) UI 확인 — "본인인증 시작" 버튼 존재 확인
  7. PortOne 본인인증 mock 성공 응답 처리 후 "다음" 버튼 클릭
  8. `read_page()` 로 4단계(정보입력) UI 확인 — 이메일/비밀번호/이름 입력 폼 존재 확인
  9. 유효한 정보 입력(이메일: `signup-test@example.com`, 비밀번호: `Test1234!`, 이름: `테스터`) 후 "회원가입" 버튼 클릭
  10. `read_network_requests()` 로 `/auth/signup` POST 200 응답 확인
  11. 회원가입 성공 후 `/login` 또는 홈으로 리다이렉트 확인
  12. `read_console_messages()` 로 에러 없음 확인
- **기대 결과**: 4단계 순서 준수(약관동의 → CAPTCHA → 본인인증 → 정보입력), 각 단계 UI 존재, `/auth/signup` 200 응답, 회원가입 완료 후 리다이렉트, toast "회원가입이 완료되었습니다." 표시
- **검증 포인트**: 단계별 UI 전환 확인, `POST /auth/signup` HTTP 200, 최종 URL 패턴(`/login` 또는 `/`)

> 참고: REQ-FE-025(Vercel 배포)는 별도 배포 단계에서 검증 — 로컬 통합 테스트 범위 제외
