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
local queue_key = KEYS[1]
local count = tonumber(ARGV[1]) or 10

-- 상위 N명 조회
local members = redis.call('ZRANGE', queue_key, 0, count - 1)

if #members == 0 then
  return {}
end

-- 대기열에서 제거
redis.call('ZREM', queue_key, unpack(members))

-- Token 발급 (별도 로직)
return members
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

1. **Event Service에서 SOLD 좌석 조회 (Feign 호출)**:
   ```java
   // Feign Client
   List<String> soldSeatIds = eventServiceClient.getSoldSeats(eventId);
   ```

2. **Redis에서 HOLD 좌석 조회**:
   ```java
   // Redis keys pattern: seat:hold:{eventId}:{seatId}
   Set<String> holdSeatIds = redisTemplate.keys("seat:hold:" + eventId + ":*")
       .stream()
       .map(key -> key.split(":")[3])  // Extract seatId
       .collect(Collectors.toSet());
   ```

3. **Event Service에서 전체 좌석 조회** (또는 클라이언트가 이미 가지고 있음):
   ```java
   List<Seat> allSeats = eventServiceClient.getEventSeats(eventId);
   ```

4. **좌석 상태 병합**:
   ```java
   return allSeats.stream()
       .map(seat -> {
           if (soldSeatIds.contains(seat.getId())) {
               seat.setStatus("SOLD");
           } else if (holdSeatIds.contains(seat.getId())) {
               seat.setStatus("HOLD");
           } else {
               seat.setStatus("AVAILABLE");
           }
           return seat;
       })
       .collect(Collectors.toList());
   ```

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

### 3.2 SAGA 패턴 구현

**성공 시나리오:**
```
Payment (Orchestrator)
├─ PortOne 결제 성공
├─ Kafka: PaymentSuccess 발행
├─ Reservation Consumer: 예매 확정 (CONFIRMED)
└─ Event Consumer: 좌석 상태 SOLD
```

**실패 시나리오 (보상 트랜잭션):**
```
Payment (Orchestrator)
├─ PortOne 결제 실패
├─ Kafka: PaymentFailed 발행
├─ Reservation Consumer: 예매 취소 (CANCELLED)
└─ Event Consumer: 좌석 선점 해제 (AVAILABLE)
```

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
