# 도메인 로직 상세 설계

## 1. 대기열 시스템 설계

### 1.1 Redis Sorted Set 기반 대기열

**구조:**
- Key: `queue:{eventId}`
- Score: Unix Timestamp (진입 시각)
- Member: userId

**진입 플로우:**
1. 중복 대기 확인: `EXISTS queue:active:{userId}`
2. 용량 확인: `ZCARD queue:{eventId}` < 50,000
3. 대기열 진입: `ZADD queue:{eventId} NX {timestamp} {userId}`
4. Active 표시: `SET queue:active:{userId} {eventId} EX 600`
5. Queue Token 발급: `qr_xxx` (Reservation Token)

**관련 요구사항:** REQ-QUEUE-001, REQ-QUEUE-010, REQ-QUEUE-021

### 1.2 배치 승인 로직 (Lua 스크립트)

**스케줄러:** 1초마다 10명 승인 (36,000명/시간)

```lua
-- queue_approve_batch.lua (Complete with token generation)
local queue_key = KEYS[1]          -- queue:{eventId}
local token_prefix = ARGV[1]       -- 'qr_'
local count = tonumber(ARGV[2]) or 10
local ttl = tonumber(ARGV[3]) or 600
local event_id = ARGV[4]

-- 상위 N명 조회 (score 기준 오름차순)
local members = redis.call('ZRANGE', queue_key, 0, count - 1)

if #members == 0 then
  return {}
end

-- 대기열에서 제거 (원자적)
redis.call('ZREM', queue_key, unpack(members))

-- Token 발급 및 저장 (원자적)
local tokens = {}
for i, user_id in ipairs(members) do
  local token = token_prefix .. redis.call('INCR', 'queue:token:counter')
  local token_data = {
    userId = user_id,
    eventId = event_id,
    type = (token_prefix == 'qr_' and 'RESERVATION' or 'PAYMENT'),
    issuedAt = redis.call('TIME')[1]
  }

  redis.call('SET', 'queue:token:' .. token,
    cjson.encode(token_data), 'EX', ttl)

  -- 사용자 활성 대기 해제
  redis.call('DEL', 'queue:active:' .. user_id)

  tokens[i] = {userId = user_id, token = token}
end

return cjson.encode(tokens)
```

**Java 호출 예시:**

```java
@Service
public class QueueBatchApprover {

    @Scheduled(fixedRate = 1000)  // 1초마다 10명 승인
    public void approveBatch() {
        String eventId = "...";  // 현재 진행 중 공연

        String result = redisTemplate.execute(
            queueApproveBatchScript,
            List.of("queue:" + eventId),
            "qr_", "10", "600", eventId
        );

        List<TokenResult> tokens = objectMapper.readValue(result,
            new TypeReference<List<TokenResult>>() {});

        // SSE or WebSocket으로 사용자에게 토큰 전달
        tokens.forEach(token ->
            sseService.sendToken(token.getUserId(), token.getToken())
        );
    }
}
```

**관련 요구사항:** REQ-QUEUE-005

### 1.3 Queue Token 이중 모델

**Reservation Token (qr_xxx):**
- 좌석 선점 시 검증
- TTL: 10분

**Payment Token (qp_xxx):**
- 결제 시 검증
- TTL: 10분
- Reservation Token 승인 후 발급

**관련 요구사항:** REQ-QUEUE-004, REQ-GW-021

### 1.4 Queue Token 만료 시 사용자 경험 (UX Flow)

Token TTL은 10분으로 설정되어 있지만, 사용자가 좌석 선택 및 결제 과정에서 시간 초과할 경우 명확한 UX 플로우가 필요합니다.

#### 시나리오 1: Reservation Token 만료 (좌석 선택 중)

**타임라인:**
- T+0: 대기열 승인, Reservation Token (`qr_abc123`) 발급
- T+10:00: 좌석 조회 요청 (`GET /events/{eventId}/seats`)
- T+10:30: Token 만료로 401 응답

**Frontend 처리 (TypeScript):**

```typescript
// React Query 또는 Axios Interceptor
const handleSeatSelection = async (eventId: string) => {
  try {
    const response = await apiClient.get(`/events/${eventId}/seats`, {
      headers: { 'X-Queue-Token': queueToken }
    });
    return response.data;
  } catch (error) {
    if (error.response?.status === 401) {
      const errorCode = error.response.data.errorCode;

      if (errorCode === 'TOKEN_EXPIRED') {
        // Token 만료 알림 표시
        toast.error('대기 시간이 초과되었습니다. 다시 대기열에 진입해주세요.');

        // 대기열 페이지로 리디렉션
        router.push(`/queue/${eventId}`);

        // 로컬 스토리지에서 Token 제거
        localStorage.removeItem('queueToken');
      } else if (errorCode === 'TOKEN_INVALID') {
        toast.error('유효하지 않은 대기 토큰입니다.');
        router.push(`/queue/${eventId}`);
      }
    }
    throw error;
  }
};
```

**Backend 응답 (JSON):**

```json
{
  "timestamp": "2026-01-12T15:30:45Z",
  "status": 401,
  "error": "Unauthorized",
  "errorCode": "TOKEN_EXPIRED",
  "message": "Queue token has expired. Please rejoin the queue.",
  "path": "/events/12345/seats",
  "requestId": "req-uuid-xxx"
}
```

**API Gateway Filter 검증 로직 (Spring Cloud Gateway):**

```java
@Component
public class QueueTokenValidationFilter implements GatewayFilter {

    @Override
    public Mono<ServerWebExchange> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = exchange.getRequest().getHeaders().getFirst("X-Queue-Token");

        if (token == null || token.isEmpty()) {
            return buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_MISSING");
        }

        // Redis Token 검증
        String tokenData = redisTemplate.opsForValue().get("queue:token:" + token);

        if (tokenData == null) {
            return buildErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
        }

        // Token 데이터 파싱 및 userId, eventId 검증
        return chain.filter(exchange);
    }

    private Mono<Void> buildErrorResponse(ServerWebExchange exchange,
                                          HttpStatus status,
                                          String errorCode) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(status.value())
            .error(status.getReasonPhrase())
            .errorCode(errorCode)
            .message(getErrorMessage(errorCode))
            .path(exchange.getRequest().getPath().value())
            .requestId(exchange.getRequest().getId())
            .build();

        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(objectMapper.writeValueAsBytes(error));

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
```

#### 시나리오 2: Payment Token 만료 (PortOne 결제 중)

**타임라인:**
- T+0: 좌석 선점 완료, Payment Token (`qp_xyz789`) 발급
- T+9:30: 사용자가 PortOne 결제 UI 진입
- T+10:30: PortOne 결제 성공 (카드 승인 완료)
- T+10:31: Payment Callback 요청 → **Token 검증 실패 (만료)**

**문제점:**
- 결제는 성공했으나 Payment Token이 만료되어 예매 생성 실패
- 사용자는 결제 완료 알림을 받았지만 시스템에서는 예매 미확정
- **환불 또는 수동 복구 필요 (Customer Support 개입)**

**해결 방안: PortOne Callback에서 Token 검증 제외**

```java
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    /**
     * PortOne Webhook Callback
     * - Token 검증 없이 imp_uid, merchant_uid만으로 검증
     * - 이미 결제 성공 상태이므로 Token TTL과 무관하게 처리
     */
    @PostMapping("/callback")
    public ResponseEntity<PaymentCallbackResponse> handlePortOneCallback(
        @RequestBody PortOneCallbackRequest request
    ) {
        String impUid = request.getImpUid();         // PortOne 거래 ID
        String merchantUid = request.getMerchantUid(); // 예매 ID (우리 시스템)

        // 1. PortOne Confirm API로 결제 금액 검증 (필수)
        IamportResponse<Payment> portOnePayment = iamportClient.paymentByImpUid(impUid);

        if (!portOnePayment.getResponse().getStatus().equals("paid")) {
            return ResponseEntity.badRequest().body(
                PaymentCallbackResponse.fail("Payment not completed")
            );
        }

        // 2. merchantUid로 예매 정보 조회 (Token 검증 없음)
        Reservation reservation = reservationRepository.findByMerchantUid(merchantUid)
            .orElseThrow(() -> new ReservationNotFoundException(merchantUid));

        // 3. 금액 검증 (실제 결제 금액 vs 예매 금액)
        if (!portOnePayment.getResponse().getAmount().equals(reservation.getTotalAmount())) {
            // 금액 불일치 → 환불 처리
            iamportClient.cancelPaymentByImpUid(impUid,
                CancelData.builder().reason("Amount mismatch").build());

            return ResponseEntity.badRequest().body(
                PaymentCallbackResponse.fail("Payment amount mismatch")
            );
        }

        // 4. 예매 확정 (Token TTL과 무관)
        Payment payment = paymentService.confirmPayment(
            reservation.getId(),
            impUid,
            portOnePayment.getResponse().getAmount()
        );

        // 5. PaymentSuccess 이벤트 발행 (Kafka)
        paymentEventPublisher.publishPaymentSuccess(payment);

        return ResponseEntity.ok(PaymentCallbackResponse.success(payment.getId()));
    }

    /**
     * 결제 요청 엔드포인트 (Token 검증 포함)
     * - 이 단계에서는 Payment Token 필수 검증
     */
    @PostMapping("/request")
    public ResponseEntity<PaymentRequestResponse> requestPayment(
        @RequestHeader("X-Payment-Token") String paymentToken,
        @RequestBody PaymentRequest request
    ) {
        // Token 검증 (API Gateway에서 이미 검증하지만 추가 확인)
        TokenData tokenData = queueService.validatePaymentToken(paymentToken);

        // 예매 정보 조회 및 결제 준비
        Payment payment = paymentService.preparePayment(
            request.getReservationId(),
            request.getAmount(),
            tokenData.getUserId()
        );

        return ResponseEntity.ok(PaymentRequestResponse.from(payment));
    }
}
```

**핵심 전략:**
- **결제 요청 단계**: Payment Token 필수 검증 (Token 유효 시간 내에만 결제 시작 가능)
- **PortOne Callback 단계**: Token 검증 제외 (이미 결제 성공 상태, merchantUid와 금액 검증으로 대체)
- **장점**: Token 만료로 인한 결제 성공 + 예매 실패 불일치 방지

#### 시나리오 3: Frontend Timer 표시 (권장 UX)

사용자에게 Token 만료 시간을 시각적으로 표시하여 시간 내 작업 완료를 유도합니다.

**React Component 예시:**

```typescript
import React, { useState, useEffect } from 'react';
import { differenceInSeconds } from 'date-fns';

interface QueueTokenTimerProps {
  tokenIssuedAt: Date;  // Token 발급 시각 (서버에서 전달)
  ttlSeconds: number;   // TTL (600초 = 10분)
  onExpired: () => void;
}

export const QueueTokenTimer: React.FC<QueueTokenTimerProps> = ({
  tokenIssuedAt,
  ttlSeconds,
  onExpired
}) => {
  const [remainingSeconds, setRemainingSeconds] = useState(ttlSeconds);

  useEffect(() => {
    const interval = setInterval(() => {
      const elapsed = differenceInSeconds(new Date(), tokenIssuedAt);
      const remaining = ttlSeconds - elapsed;

      if (remaining <= 0) {
        clearInterval(interval);
        setRemainingSeconds(0);
        onExpired(); // 만료 시 콜백 실행 (알림 + 리디렉션)
      } else {
        setRemainingSeconds(remaining);
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [tokenIssuedAt, ttlSeconds, onExpired]);

  const minutes = Math.floor(remainingSeconds / 60);
  const seconds = remainingSeconds % 60;

  // 2분 미만 시 경고 색상
  const isWarning = remainingSeconds < 120;

  return (
    <div className={`timer-container ${isWarning ? 'warning' : ''}`}>
      <div className="timer-icon">⏱️</div>
      <div className="timer-text">
        남은 시간: {minutes}분 {seconds.toString().padStart(2, '0')}초
      </div>
      {isWarning && (
        <div className="timer-warning">
          ⚠️ 곧 대기 시간이 만료됩니다. 서둘러 좌석을 선택해주세요.
        </div>
      )}
    </div>
  );
};

// 사용 예시
const SeatSelectionPage = () => {
  const { queueToken, tokenIssuedAt } = useQueueToken();

  const handleTokenExpired = () => {
    toast.error('대기 시간이 초과되었습니다. 다시 대기열에 진입해주세요.');
    router.push('/queue');
  };

  return (
    <div>
      <QueueTokenTimer
        tokenIssuedAt={new Date(tokenIssuedAt)}
        ttlSeconds={600}
        onExpired={handleTokenExpired}
      />
      {/* 좌석 선택 UI */}
    </div>
  );
};
```

#### 선택 기능 (Future): Token 연장 API

현재는 **MVP에서 구현하지 않음** (YAGNI 원칙). 향후 사용자 피드백 및 예매 포기율 데이터 분석 후 필요 시 추가 검토.

**API 설계 (안):**

```
POST /api/queue/token/extend
Headers:
  - X-Queue-Token: qr_xxx (현재 Token)

Response:
  - newToken: qr_yyy (새 Token, 5분 연장)
  - expiresAt: "2026-01-12T15:45:00Z"
```

**제약 조건:**
- 1회만 연장 가능 (Redis에 `token:extended:{token}` 플래그 저장)
- 만료 2분 전부터 요청 가능
- 최대 5분 연장 (총 15분)

**Redis 구현 (Lua 스크립트):**

```lua
-- queue_token_extend.lua
local token_key = KEYS[1]           -- queue:token:qr_xxx
local extended_flag_key = KEYS[2]   -- token:extended:qr_xxx
local extend_ttl = tonumber(ARGV[1]) or 300  -- 5분 연장

-- 이미 연장한 Token인지 확인
if redis.call('EXISTS', extended_flag_key) == 1 then
  return {err = "TOKEN_ALREADY_EXTENDED"}
end

-- 기존 Token이 존재하는지 확인
if redis.call('EXISTS', token_key) == 0 then
  return {err = "TOKEN_NOT_FOUND"}
end

-- Token TTL 연장
redis.call('EXPIRE', token_key, extend_ttl)

-- 연장 플래그 설정 (7일 보관)
redis.call('SET', extended_flag_key, '1', 'EX', 604800)

return {ok = "TOKEN_EXTENDED"}
```

**현재 결정:** Token 연장은 구현하지 않음. 10분 TTL로 충분하며, 연장 로직은 악용 가능성(대기열 무한 점유) 및 복잡도 증가 우려.

**관련 요구사항:** REQ-QUEUE-004, REQ-GW-021, REQ-PAY-008

---

## 2. 좌석 예매 시스템 설계

### 2.1 좌석 선점 메커니즘 (Redisson 분산 락)

**프로세스:**
1. Queue Token 검증: `GET queue:token:qr_xxx`
2. 좌석 락 획득: `RLock.tryLock("seat:hold:{eventId}:{seatId}", 3초, 300초)`
3. 예매 정보 저장: `reservations` 테이블 (PENDING)
4. Payment Token 발급 요청

**TTL:** 5분 (300초)

**관련 요구사항:** REQ-RSV-001, REQ-RSV-007

### 2.2 좌석 상태 관리

| 상태 | 저장 위치 | 의미 |
|------|----------|------|
| HOLD | Redis (분산 락) | 선점 중 (5분) |
| SOLD | RDB (seats.status) | 결제 완료 |
| AVAILABLE | - | HOLD도 SOLD도 아님 |

**조회 로직:**

```java
// ❌ 기존 (Production 블로킹 발생)
// Set<String> holdSeatIds = redisTemplate.keys("seat:hold:" + eventId + ":*")

// ✅ 수정 (O(1) SET 조회)
Set<String> holdSeatIds = redisTemplate.opsForSet().members("hold_seats:" + eventId);

// Event Service 내부 API: SOLD 좌석 조회
Set<String> soldSeatIds = eventServiceClient.getInternalSoldSeats(eventId);

// 3가지 상태 구분
List<Seat> seats = allSeats.stream()
    .map(seat -> {
        if (soldSeatIds.contains(seat.getId())) {
            seat.setStatus(SeatStatus.SOLD);
        } else if (holdSeatIds.contains(seat.getId())) {
            seat.setStatus(SeatStatus.HOLD);
        } else {
            seat.setStatus(SeatStatus.AVAILABLE);
        }
        return seat;
    })
    .collect(Collectors.toList());
```

**변경 이유:**
- Redis KEYS는 O(N) 복잡도로 전체 DB 스캔 (production 금지)
- `hold_seats:{eventId}` SET은 좌석 선점/해제 시 원자적으로 업데이트
- SET 조회는 O(N)이지만 N은 실제 HOLD된 좌석 수 (최대 4 × 동시 예매 수)

**관련 요구사항:** REQ-RSV-003, REQ-EVT-008

### 2.3 예매 확정 플로우 (Kafka Consumer)

**이벤트 수신:** `payment.events` (PaymentSuccess)

**처리 로직:**
1. 멱등성 체크: paymentKey 중복 확인
2. 예매 상태 변경: PENDING → CONFIRMED
3. Outbox 이벤트 발행: ReservationConfirmed (Event Service로 전파)

**관련 요구사항:** REQ-RSV-004, REQ-RSV-011

---

## 3. 결제 시스템 설계

### 3.1 PortOne 통합 아키텍처

**결제 프로세스:**
1. **결제 정보 생성:** Payment 레코드 생성 (PENDING)
2. **PortOne Prepare API:** 금액 사전 검증 등록
3. **클라이언트 결제:** PortOne JS SDK로 결제 UI 호출
4. **PortOne Confirm API:** 결제 승인 확인 및 금액 검증
5. **결제 완료:** Payment 상태 SUCCESS, Kafka 이벤트 발행

**Timeout:** 10초 (REQ-PAY-008)
**Circuit Breaker:** 실패율 50% 초과 시 Open, 대기 60초 (REQ-PAY-009)

**관련 요구사항:** REQ-PAY-001, REQ-PAY-006, REQ-PAY-007, REQ-PAY-010

### 3.2 SAGA 패턴 - 보상 트랜잭션

#### 성공 플로우 (좌석 SOLD 업데이트)
```
Payment Service (Orchestrator)
├─ PortOne 결제 성공
├─ Payment 상태: PENDING → PAID
├─ PaymentSuccess 이벤트 발행 (Outbox → payment.events)
│
Reservation Service Consumer
├─ payment.events 구독
├─ PaymentSuccess 수신 (멱등성: processed_events 체크)
├─ 예매 상태: PENDING → CONFIRMED
├─ ReservationConfirmed 이벤트 발행 (reservation.events)
│
Event Service Consumer
├─ reservation.events 구독
├─ ReservationConfirmed 수신
└─ 좌석 상태 업데이트: seats.status = SOLD (RDB)
    (Redis hold_seats는 그대로 유지 - 예매 완료 표시)
```

#### 실패 플로우 (좌석 HOLD 해제)
```
Payment Service (Orchestrator)
├─ PortOne 결제 실패
├─ PaymentFailed 이벤트 발행 (Outbox → payment.events)
│
Reservation Service Consumer
├─ payment.events 구독
├─ PaymentFailed 수신
├─ 예매 상태: PENDING → CANCELLED
├─ 분산 락 해제: lock.unlock() (seat:hold:{eventId}:{seatId})
├─ ReservationCancelled 이벤트 발행 (reservation.events)
│
Event Service Consumer
├─ reservation.events 구독
├─ ReservationCancelled 수신
└─ Redis hold_seats에서 제거: SREM hold_seats:{eventId} {seatId}
    (RDB seats.status는 AVAILABLE 유지 - 원래 상태)
```

**책임 명확화:**
- **SOLD 업데이트**: Event Service (ReservationConfirmed 이벤트 수신 시)
- **HOLD 해제**: Reservation Service (분산 락 해제) + Event Service (Redis SET 정리)

**관련 요구사항:** REQ-PAY-011, REQ-PAY-012

### 3.3 멱등성 보장 (paymentKey)

**paymentKey:** 클라이언트가 생성한 UUID (Unique)

**중복 방지:**
```java
if (paymentRepository.existsByPaymentKey(paymentKey)) {
    Payment existing = paymentRepository.findByPaymentKey(paymentKey);
    return existing;  // 중복 요청, 기존 결과 반환
}
```

**관련 요구사항:** REQ-PAY-004, REQ-PAY-010
