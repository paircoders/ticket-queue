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

#### 1.2.4 Queue Token 검증 레이어 (REQ-QUEUE-016, REQ-RSV-008)

**결정: 2-layer validation (Gateway + Service)**

##### Layer 1: API Gateway (형식 검증)
- X-Queue-Token 헤더 존재 여부 확인
- 형식 검증: `qr_` 또는 `qp_` prefix + UUID 형식
- 없거나 형식 오류 시: 400 Bad Request
- 통과 시: 헤더 그대로 다운스트림 전달 (유효성 미검증)

**적용 경로:**
- POST /reservations/hold (qr_ 필수)
- POST /payments (qp_ 필수)

##### Layer 2: Reservation/Payment Service (유효성 검증 - 필수)
**방식 A (채택): Redis 직접 조회**
```java
@Component
public class QueueTokenValidator {
    public void validate(String token, String userId, String eventId) {
        String key = "queue:token:" + token;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            throw new InvalidQueueTokenException("Token expired or invalid");
        }

        QueueToken queueToken = objectMapper.readValue(value, QueueToken.class);
        if (!queueToken.getUserId().equals(userId)) {
            throw new UnauthorizedQueueTokenException("Token user mismatch");
        }
        if (!queueToken.getEventId().equals(eventId)) {
            throw new InvalidQueueTokenException("Token event mismatch");
        }
    }
}
```

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
| POST | `/payments` | 결제 요청 | 필수 + Queue Token | 20/분 (사용자) |
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
INTERNAL_API_KEY=550e8400-e29b-41d4-a716-446655440000  # 자신의 API Key
```

**2. Feign Client - API Key 헤더 추가**
```java
@Configuration
public class FeignClientConfig {
    @Value("${internal.api-key}")
    private String apiKey;

    @Bean
    public RequestInterceptor apiKeyInterceptor() {
        return requestTemplate -> {
            requestTemplate.header("X-Service-Api-Key", apiKey);
        };
    }
}
```

**3. 서버: API Key 검증 Interceptor**
```java
@Component
public class InternalApiKeyInterceptor implements HandlerInterceptor {

    @Value("${internal.api-key}")
    private String expectedApiKey;

    @Override
    public boolean preHandle(HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler) {
        if (request.getRequestURI().startsWith("/internal/")) {
            String apiKey = request.getHeader("X-Service-Api-Key");

            if (apiKey == null || !apiKey.equals(expectedApiKey)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        }
        return true;
    }
}
```

**4. API Key Rotation (연 1회 권장)**
- 신규 Key 생성 → 모든 서비스 환경변수 업데이트 → 재배포
- 롤링 업데이트로 다운타임 최소화

### 1.4 API Rate Limiting 정책

**엔드포인트별 제한:**

| 엔드포인트 | IP 기반 | 사용자 기반 | 비고 |
|-----------|---------|-----------|------|
| 읽기 (GET 공개) | 300/분 | - | 공연 목록/상세 조회 |
| 로그인 | 10/분 | - | 무차별 대입 공격 방지 |
| 대기열 상태 조회 | 100/분 | 15/분 | 5초 폴링 권장 (REQ-QUEUE-002), 기존 60/분은 2초 폴링 유도로 과도 |
| 예매/결제 | 50/분 | 20/분 | 어뷰징 방지 |
| 기타 | 100/분 | 200/분 | 일반 API |

**구현:** Resilience4j RateLimiter + Redis 기반 분산 Rate Limiter

**관련 요구사항:** REQ-GW-005, REQ-GW-006, REQ-QUEUE-014

#### 1.4.1 Rate Limiting 적용 순서 및 체크 로직

Rate Limiting은 다층 방어 전략으로 IP 기반 어뷰징 방지 → 인증 → 사용자별 제한 순서로 적용됩니다.

**적용 순서:**

```
1. Layer 1: IP 기반 제한 (익명 + 인증 사용자 모두)
   ↓ (통과 시)
2. Layer 2: JWT 검증 (인증 필요 엔드포인트만)
   ↓ (통과 시)
3. Layer 3: 사용자 기반 제한 (인증된 사용자만)
```

**Layer 1: IP 기반 Rate Limiting (공개 API + 로그인)**
- **목적**: 무차별 대입 공격, DDoS 방지
- **적용 대상**:
  - 공개 API: GET /events, GET /events/{id} (300/분)
  - 인증 API: POST /auth/register, POST /auth/login (10/분)
- **구현**: Redis Token Bucket + Spring Cloud Gateway Filter
- **429 응답 시**: IP 차단 (블랙리스트), `Retry-After` 헤더 포함

**Layer 2: JWT 검증**
- 인증 필수 엔드포인트에서 토큰 서명, 만료, 블랙리스트 확인
- 검증 실패 시: 401 Unauthorized (Layer 3 진입 불가)

**Layer 3: 사용자 기반 Rate Limiting (인증된 사용자)**
- **목적**: 사용자별 공정한 리소스 사용, 어뷰징 방지
- **적용 대상**:
  - 대기열 조회: GET /queue/status (15/분)
  - 예매/결제: POST /reservations/hold, POST /payments (20/분)
  - 일반 API: GET /users/me, GET /reservations (200/분)
- **구현**: Redis `rate:user:{userId}:{endpoint}` (TTL 1분)

**Spring Cloud Gateway Filter 구현 예시:**

```java
@Component
public class RateLimitGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimitGatewayFilterFactory.Config> {

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            String ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();

            // Layer 1: IP 기반 제한
            if (!checkIpRateLimit(ip, config.getIpLimit())) {
                return createRateLimitResponse(exchange, "IP rate limit exceeded");
            }

            // Layer 2: JWT 검증 (인증 필요 경로만)
            if (config.isAuthRequired()) {
                String userId = extractUserIdFromJWT(exchange);
                if (userId == null) {
                    return createUnauthorizedResponse(exchange);
                }

                // Layer 3: 사용자 기반 제한
                if (!checkUserRateLimit(userId, path, config.getUserLimit())) {
                    return createRateLimitResponse(exchange, "User rate limit exceeded");
                }
            }

            return chain.filter(exchange);
        };
    }

    private boolean checkIpRateLimit(String ip, int limit) {
        String key = "rate:ip:" + ip;
        return incrementAndCheck(key, limit, 60); // 1분 TTL
    }

    private boolean checkUserRateLimit(String userId, String path, int limit) {
        String key = "rate:user:" + userId + ":" + normalizePath(path);
        return incrementAndCheck(key, limit, 60);
    }

    private boolean incrementAndCheck(String key, int limit, int ttlSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        }
        return count <= limit;
    }
}
```

**Redis Token Bucket 구현 (Lua 스크립트):**

```lua
-- rate_limit.lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

local current = redis.call('GET', key)
if current == false then
    redis.call('SET', key, 1, 'EX', ttl)
    return 1
else
    current = tonumber(current)
    if current < limit then
        redis.call('INCR', key)
        return current + 1
    else
        return -1 -- Rate limit exceeded
    end
end
```

**엔드포인트별 Rate Limit 매핑:**

| 엔드포인트 | IP 제한 (Layer 1) | 사용자 제한 (Layer 3) | 비고 |
|-----------|------------------|---------------------|------|
| GET /events, /events/{id} | 300/분 | - | 공개 API, 인증 불필요 |
| POST /auth/register | 10/분 | - | IP 기반만 (무차별 대입 방지) |
| POST /auth/login | 10/분 | - | IP 기반만 (Brute Force 방지) |
| GET /queue/status | 100/분 | 15/분 | 5초 폴링 권장, 사용자별 엄격 제한 |
| POST /queue/enter | 100/분 | 100/분 | 동시 다발 진입 제한 |
| POST /reservations/hold | 50/분 | 20/분 | 어뷰징 방지 (최대 5회/분 권장) |
| POST /payments | 50/분 | 20/분 | 결제 시도 제한 |
| GET /users/me, /reservations | 100/분 | 200/분 | 일반 조회 |

**429 Too Many Requests 응답 형식:**

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Please retry after 30 seconds.",
  "retryAfter": 30,
  "limit": 15,
  "remaining": 0,
  "resetAt": "2026-01-12T10:30:00Z"
}
```

**모니터링:**
- CloudWatch Metric: `api.rate_limit.exceeded` (엔드포인트별 집계)
- 알람: 특정 사용자가 10분 내 5회 이상 429 발생 시 어뷰징 의심

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
- **Refresh Token Rotation (RTR): 필수 구현** ✅
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
**개인정보 (선택):** AES-256-GCM (email, phone, CI)
**통신:** HTTPS/TLS 1.3

**관련 요구사항:** REQ-AUTH-014, REQ-AUTH-019

### 2.4 CORS 정책

**허용 Origin:** `https://ticketing.vercel.app` (프론트엔드)
**허용 메서드:** GET, POST, PUT, DELETE, OPTIONS
**허용 헤더:** Authorization, Content-Type, X-Queue-Token
**인증정보 포함:** credentials: true

**관련 요구사항:** REQ-GW-004
