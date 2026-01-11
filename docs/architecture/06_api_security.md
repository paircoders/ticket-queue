# API 및 보안 설계

## 1. API 설계

### 1.1 API Gateway 라우팅 규칙

**Path 기반 라우팅 매핑:**

| Path Pattern | 대상 서비스 | Port | 비고 |
|-------------|-----------|------|------|
| `/auth/**` | User Service | 8081 | 회원가입, 로그인, 로그아웃, 토큰 갱신 |
| `/users/**` | User Service | 8081 | 프로필 관리 |
| `/events/**` | Event Service | 8082 | 공연 조회, 좌석 정보 (공개 + 인증) |
| `/venues/**` | Event Service | 8082 | 공연장/홀 관리 (관리자) |
| `/queue/**` | Queue Service | 8083 | 대기열 진입, 상태 조회 |
| `/reservations/**` | Reservation Service | 8084 | 예매 관리 |
| `/payments/**` | Payment Service | 8085 | 결제 처리 |

**공개 엔드포인트 (인증 불필요):**
- `POST /auth/register`
- `POST /auth/login`
- `GET /events`, `GET /events/{id}`
- `GET /venues`

**인증 필수 엔드포인트:**
- `/auth/logout`, `/auth/refresh`
- `/users/**`
- `/queue/**`, `/reservations/**`, `/payments/**`

**관리자 전용 엔드포인트:**
- `POST /events`, `PUT /events/{id}`, `DELETE /events/{id}`
- `POST /venues`, `PUT /venues/{id}`
- `GET /queue/admin/stats`

**관련 요구사항:** REQ-GW-001 (동적 라우팅), REQ-GW-003 (공개 엔드포인트), REQ-GW-020 (Admin 권한)

### 1.2 주요 API 엔드포인트 목록

#### 1.2.1 인증/회원 API (User Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| POST | `/auth/register` | 회원가입 | 불필요 | 10/분 (IP) |
| POST | `/auth/login` | 로그인 | 불필요 | 10/분 (IP) |
| POST | `/auth/logout` | 로그아웃 | 필수 | 200/분 (사용자) |
| POST | `/auth/refresh` | 토큰 갱신 | Refresh Token | 200/분 (사용자) |
| GET | `/users/me` | 프로필 조회 | 필수 | 200/분 (사용자) |
| PUT | `/users/me` | 프로필 수정 | 필수 | 100/분 (사용자) |

**관련 요구사항:** REQ-AUTH-001 ~ REQ-AUTH-021

#### 1.2.2 공연 관리 API (Event Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| GET | `/events` | 공연 목록 조회 | 불필요 | 300/분 (IP) |
| GET | `/events/{id}` | 공연 상세 조회 | 불필요 | 300/분 (IP) |
| GET | `/events/{id}/seats` | 좌석 정보 조회 | 필수 | 200/분 (사용자) |
| POST | `/events` | 공연 생성 | 관리자 | - |
| PUT | `/events/{id}` | 공연 수정 | 관리자 | - |
| POST | `/venues` | 공연장 생성 | 관리자 | - |
| GET | `/internal/seats/status/{eventId}` | SOLD 좌석 ID 조회 (내부 전용) | 불필요 (내부) | - |

**참고:** `/internal/**` 경로는 API Gateway를 거치지 않고 서비스 간 직접 호출됩니다.

**관련 요구사항:** REQ-EVT-001 ~ REQ-EVT-006

#### 1.2.3 대기열 API (Queue Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| POST | `/queue/enter` | 대기열 진입 | 필수 | 100/분 (사용자) |
| GET | `/queue/status` | 대기열 상태 조회 | 필수 | 60/분 (사용자) |
| DELETE | `/queue/leave` | 대기열 이탈 | 필수 | 100/분 (사용자) |

**관련 요구사항:** REQ-QUEUE-001, REQ-QUEUE-002, REQ-QUEUE-014

#### 1.2.4 예매 API (Reservation Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| GET | `/reservations/seats/{eventId}` | 좌석 상태 조회 | 필수 | 200/분 (사용자) |
| POST | `/reservations/hold` | 좌석 선점 | 필수 + Queue Token | 20/분 (사용자) |
| PUT | `/reservations/hold/{id}` | 좌석 변경 | 필수 | 20/분 (사용자) |
| GET | `/reservations` | 나의 예매 내역 | 필수 | 200/분 (사용자) |
| DELETE | `/reservations/{id}` | 예매 취소 | 필수 | 20/분 (사용자) |

**관련 요구사항:** REQ-RSV-001 ~ REQ-RSV-009

#### 1.2.5 결제 API (Payment Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| POST | `/payments` | 결제 요청 | 필수 + Queue Token | 20/분 (사용자) |
| POST | `/payments/confirm` | 결제 확인 | 필수 | 20/분 (사용자) |
| GET | `/payments/{id}` | 결제 조회 | 필수 | 200/분 (사용자) |
| GET | `/payments` | 결제 내역 | 필수 | 200/분 (사용자) |

**관련 요구사항:** REQ-PAY-001 ~ REQ-PAY-015

### 1.3 API Rate Limiting 정책

**엔드포인트별 제한:**

| 엔드포인트 | IP 기반 | 사용자 기반 | 비고 |
|-----------|---------|-----------|------|
| 읽기 (GET 공개) | 300/분 | - | 공연 목록/상세 조회 |
| 로그인 | 10/분 | - | 무차별 대입 공격 방지 |
| 대기열 상태 조회 | - | 60/분 | 폴링 제한 |
| 예매/결제 | 50/분 | 20/분 | 어뷰징 방지 |
| 기타 | 100/분 | 200/분 | 일반 API |

**구현:** Resilience4j RateLimiter + Redis 기반 분산 Rate Limiter

**관련 요구사항:** REQ-GW-005, REQ-GW-006, REQ-QUEUE-014

---

## 2. 보안 아키텍처

### 2.1 JWT 인증/인가 플로우

**Access Token:**
- 유효기간: 1시간
- 포함 정보: userId, email, role (USER/ADMIN)
- 서명 알고리즘: HS256 (HMAC-SHA256)

**Refresh Token:**
- 유효기간: 7일
- DB 저장 (auth_tokens 테이블)
- Rotation (RTR) 적용 검토

**토큰 블랙리스트:**
- 로그아웃 시 Access Token을 Redis에 블랙리스트 등록
- TTL: 토큰 만료 시간 (1시간)

**API Gateway 검증 플로우:**
```
1. 클라이언트 → Gateway: Authorization: Bearer {token}
2. Gateway: JWT 서명 검증, 만료 확인
3. Gateway: Redis 블랙리스트 확인
4. Gateway: 사용자 정보 추출 (userId, role)
5. Gateway → 다운스트림: X-User-Id, X-User-Role 헤더 추가
```

**관련 요구사항:** REQ-AUTH-006, REQ-AUTH-009, REQ-AUTH-010, REQ-AUTH-011, REQ-GW-002

### 2.2 본인인증 및 CAPTCHA

**PortOne 본인인증 (CI/DI):**
- 회원가입 시 필수
- CI (Connecting Information): 1인 1계정 강제
- 테스트 모드 사용

**reCAPTCHA:**
- 회원가입, 로그인 시 검증
- v2 Checkbox 방식

**관련 요구사항:** REQ-AUTH-003, REQ-AUTH-004

### 2.3 암호화 전략

**비밀번호:** BCrypt (Cost Factor: 10-12)
**개인정보 (선택):** AES-256-GCM (email, phone, CI)
**통신:** HTTPS/TLS 1.3

**관련 요구사항:** REQ-AUTH-014, REQ-AUTH-019

### 2.4 CORS 정책

**허용 Origin:** `https://ticketing.vercel.app` (프론트엔드)
**허용 메서드:** GET, POST, PUT, DELETE, OPTIONS
**허용 헤더:** Authorization, Content-Type, X-Queue-Token
**인증정보 포함:** credentials: true

**관련 요구사항:** REQ-GW-004
