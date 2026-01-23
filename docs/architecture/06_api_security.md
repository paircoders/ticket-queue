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
- `POST /auth/signup`
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
| POST | `/auth/signup` | 회원가입 | 불필요 | 10/분 (IP) |
| POST | `/auth/login` | 로그인 | 불필요 | 10/분 (IP) |
| POST | `/auth/logout` | 로그아웃 | 필수 | 200/분 (사용자) |
| POST | `/auth/refresh` | 토큰 갱신 | Refresh Token | 200/분 (사용자) |
| GET | `/users/me` | 프로필 조회 | 필수 | 200/분 (사용자) |
| PUT | `/users/me` | 프로필 수정 | 필수 | 100/분 (사용자) |

**관련 요구사항:** REQ-AUTH-001 ~ REQ-AUTH-020

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

**참고:** `/internal/**` 경로는 API Gateway를 거치지 않고 서비스 간 직접 호출

**관련 요구사항:** REQ-EVT-001 ~ REQ-EVT-006

#### 1.2.3 대기열 API (Queue Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| POST | `/queue/enter` | 대기열 진입 | 필수 | 100/분 (사용자) |
| GET | `/queue/status` | 대기열 상태 조회 | 필수 | 60/분 (사용자) |
| DELETE | `/queue/leave` | 대기열 이탈 | 필수 | 100/분 (사용자) |

**관련 요구사항:** REQ-QUEUE-001, REQ-QUEUE-002, REQ-QUEUE-008

#### 1.2.4 Queue Token 검증 레이어 (REQ-QUEUE-010, REQ-RSV-008)

**결정: 2-layer validation (Gateway + Service)**

##### Layer 1: API Gateway (형식 검증)
- X-Queue-Token 헤더 존재 여부 확인
- 형식 검증: `qr_` prefix + UUID 형식
- 없거나 형식 오류 시: 400 Bad Request
- 통과 시: 헤더 그대로 다운스트림 전달 (유효성 미검증)

**적용 경로:**
- POST /reservations/hold (qr_ 필수)

##### Layer 2: Reservation/Payment Service (유효성 검증 - 필수)
**방식 A (채택): Redis 직접 조회**

**채택 이유:**
- 성능: 추가 RPC 호출 불필요 (Redis 접근은 기존 인프라)
- 신뢰성: Queue Service 장애에 독립적

#### 1.2.5 예매 API (Reservation Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| GET | `/reservations/seats/{eventId}` | 좌석 상태 조회 | 필수 | 200/분 (사용자) |
| POST | `/reservations/hold` | 좌석 선점 | 필수 + Queue Token | 20/분 (사용자) |
| PUT | `/reservations/hold/{id}` | 좌석 변경 | 필수 | 20/분 (사용자) |
| GET | `/reservations` | 나의 예매 내역 | 필수 | 200/분 (사용자) |
| DELETE | `/reservations/{id}` | 예매 취소 | 필수 | 20/분 (사용자) |

**관련 요구사항:** REQ-RSV-001 ~ REQ-RSV-009

#### 1.2.6 결제 API (Payment Service)

| Method | Endpoint | 설명 | 인증 | Rate Limit |
|--------|----------|------|------|-----------|
| POST | `/payments` | 결제 요청 | 필수 | 20/분 (사용자) |
| POST | `/payments/confirm` | 결제 확인 | 필수 | 20/분 (사용자) |
| GET | `/payments/{id}` | 결제 조회 | 필수 | 200/분 (사용자) |
| GET | `/payments` | 결제 내역 | 필수 | 200/분 (사용자) |

**관련 요구사항:** REQ-PAY-001 ~ REQ-PAY-015

### 1.3 내부 API 인증 (REQ-INT-001 ~ REQ-INT-010)

**방식: API Key (UUID v4 기반) - 단일 방식 확정**

#### 구현 상세

**1. API Key 발급 및 저장**
- 각 서비스별 고유 UUID v4 생성
- AWS Secrets Manager 또는 환경변수 저장

환경변수 예시:
```bash
# 각 서비스의 .env 또는 ECS Task Definition
# [보안 경고] 실제 배포 시에는 반드시 새로 생성한 UUID를 사용
INTERNAL_API_KEY=<GENERATED_UUID_V4>  # 예: 550e8400-e29b-41d4-a716-446655440000
```

**2. Feign Client - API Key 헤더 추가**

**3. 서버: API Key 검증 Interceptor**

**4. API Key Rotation (연 1회 권장)**
- 신규 Key 생성 → 모든 서비스 환경변수 업데이트 → 재배포
- 롤링 업데이트로 다운타임 최소화

### 1.4 API Rate Limiting 정책

**전략: 대기열 중심의 트래픽 제어 (Queue-Based Throttling)**

복잡한 IP/사용자 기반의 Rate Limiting을 API Gateway에서 직접 구현하는 대신, **대기열 시스템(Queue Service)**이 전체 트래픽의 유입량을 제어하는 핵심 역할을 수행합니다.

**핵심 로직:**
1. **대기열 토큰 검증**: `Queue Token`이 유효한 경우에만 주요 API(예매, 결제) 접근 허용.
2. **토큰 발급 제어**: Queue Service에서 서버가 처리 가능한 속도(예: 10 TPS)로만 토큰을 발급(승인)함.
3. **결과**: 자연스럽게 백엔드 유입 트래픽이 제어됨.

**엔드포인트별 제한:**

| 엔드포인트 | 제어 방식 | 비고 |
|-----------|---------|------|
| 대기열 진입 | Redis ZADD | 대기열 자체의 처리량은 Redis 성능에 의존 (매우 높음) |
| 대기열 조회 | 5초 폴링 권장 | 클라이언트 사이드 제어 유도 |
| 예매/결제 | **Queue Token 필수** | 토큰이 없거나 만료되면 401 Unauthorized |
| 로그인/일반 | 기본 Throttling | Spring Cloud Gateway 기본 RequestRateLimiter 사용 (선택) |

**관련 요구사항:** REQ-GW-006, REQ-QUEUE-008

#### 1.4.1 API Gateway 필터 구현

---

## 2. 보안 아키텍처

### 2.1 JWT 인증/인가 플로우

**Access Token:**
- 유효기간: 1시간
- 포함 정보: userId, email, role (USER/ADMIN)
- 서명 알고리즘: HS256 (HMAC-SHA256)

**Refresh Token:**
- 유효기간: 7일
- DB 저장 (auth_tokens 테이블, token_family 기반 RTR)
- **Refresh Token Rotation (RTR): 필수 구현**
  - 매 토큰 갱신 시 신규 Refresh Token 발급
  - 기존 토큰은 즉시 폐기 (revoked=true)
  - Token Family로 탈취 감지 (동일 Family 재사용 시 전체 무효화)

**토큰 블랙리스트:**
- 로그아웃 시 Access Token을 Redis에 블랙리스트 등록
- TTL: 토큰 만료 시간 (1시간)

### 2.1.1 RTR 구현 상세 (REQ-AUTH-012)

**토큰 갱신 플로우:**
1. Client → POST /auth/refresh (Refresh Token 포함)
2. Server: auth_tokens 테이블 조회 (refresh_token, revoked=false)
3. 유효성 검증 (만료 여부, 폐기 여부)
4. 신규 토큰 쌍 생성:
   - Access Token (1시간)
   - Refresh Token (7일, 동일 token_family)
5. 기존 Refresh Token 폐기 (revoked=true, revoked_at=now())
6. 신규 Refresh Token DB 저장
7. 응답: 신규 토큰 쌍 반환

**탈취 감지:**
- 이미 폐기된 Refresh Token 사용 시 → 해당 token_family 전체 무효화
- 사용자에게 재로그인 강제 (보안 알림)

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
**개인정보:** 평문 저장 (포트폴리오 범위)
**통신:** HTTPS/TLS 1.3

**관련 요구사항:** REQ-AUTH-014

### 2.4 CORS 정책

**허용 Origin:** `https://ticketing.vercel.app` (프론트엔드)
**허용 메서드:** GET, POST, PUT, DELETE, OPTIONS
**허용 헤더:** Authorization, Content-Type, X-Queue-Token
**인증정보 포함:** credentials: true

**관련 요구사항:** REQ-GW-004
