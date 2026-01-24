# 아키텍처 패턴 및 설계 원칙

## 1. 아키텍처 패턴 및 설계 원칙

### 1.1 MSA (Microservices Architecture) 채택 이유

#### 1.1.1 채택 배경
1. **독립적 확장성**:
   - 대기열 서비스는 트래픽 폭증 시 독립적으로 스케일 아웃
   - 결제 서비스는 PG 연동 부하에 따라 별도 확장

2. **장애 격리**:
   - 결제 서비스 장애 시 예매 조회는 정상 작동
   - Circuit Breaker로 장애 전파 방지

3. **기술 스택 유연성**:
   - 서비스별 최적 기술 선택 가능 (향후 확장 고려)
   - Redis 기반 대기열, RDB 기반 예매/결제

4. **개발 생산성**:
   - A개발자 (Gateway/Event/Queue), B개발자 (User/Reservation/Payment) 병렬 개발
   - 명확한 서비스 경계로 충돌 최소화

#### 1.1.2 트레이드오프
- **복잡성 증가**: 분산 트랜잭션, 서비스 간 통신 오버헤드
  - **대응**: SAGA 패턴, Transactional Outbox 패턴으로 관리
- **운영 부담**: 6개 서비스 독립 배포, 모니터링
  - **대응**: Docker Compose, GitHub Actions로 자동화

### 1.2 도메인 주도 설계 (DDD) 적용

#### 1.2.1 Bounded Context 정의
각 마이크로서비스는 하나의 Bounded Context로 설계:

```
┌─────────────────────────────────────────────────────────┐
│  User Context                                           │
│  - 회원, 인증, 프로필                                    │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Event Context                                          │
│  - 공연, 공연장, 홀, 좌석                                │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Queue Context                                          │
│  - 대기열, 토큰                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Reservation Context                                    │
│  - 예매, 좌석 선점                                      │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Payment Context                                        │
│  - 결제, 환불                                           │
└─────────────────────────────────────────────────────────┘
```

#### 1.2.2 Aggregate 설계 원칙
- 각 서비스 내 트랜잭션 경계는 Aggregate Root로 관리
- 서비스 간 참조는 ID 기반 (Foreign Key 없음)
- 이벤트 기반 통신으로 느슨한 결합 유지

### 1.3 Event-Driven Architecture (EDA) 패턴

#### 1.3.1 적용 배경
- **비동기 처리**: 결제 성공 후 예매 확정, 좌석 상태 업데이트를 비동기로 처리
- **느슨한 결합**: 서비스 간 직접 호출 최소화, Kafka 이벤트로 통신
- **확장성**: 이벤트 발행/구독 모델로 신규 Consumer 추가 용이

#### 1.3.2 이벤트 플로우 예시

**예매 → 결제 → 확정 플로우:**
```
1. Reservation Service: 좌석 선점 및 예매 생성 (PENDING)
2. User → Payment Service: 결제 요청 (결제하기 버튼 클릭)
3. Payment Service → PortOne: 결제 승인
4. Payment Service → Kafka: PaymentSuccess 발행 (payment.events)
5. Reservation Service (Consumer): 예매 확정 (CONFIRMED)
6. Event Service (Consumer): 좌석 상태 업데이트 (SOLD)
```

**결제 실패 → 보상 트랜잭션:**
```
1. User → Payment Service: 결제 요청
2. Payment Service → PortOne: 결제 승인 시도
3. PortOne → Payment Service: 결제 실패
4. Payment Service → Kafka: PaymentFailed 발행 (payment.events)
5. Reservation Service (Consumer): 예매 취소 (CANCELLED)
6. Event Service (Consumer): 좌석 선점 해제 (AVAILABLE)
```

### 1.4 SAGA 패턴 (분산 트랜잭션)

#### 1.4.1 오케스트레이션 기반 SAGA
- **Orchestrator**: Payment Service가 SAGA 진행 관리
- **성공 시나리오**:
  1. 결제 요청 → PortOne 결제 성공
  2. Kafka 이벤트 발행 (`PaymentSuccess`)
  3. Reservation Service: 예매 확정
  4. Event Service: 좌석 상태 업데이트

- **실패 시나리오 (보상 트랜잭션)**:
  1. 결제 요청 → PortOne 결제 실패
  2. Kafka 이벤트 발행 (`PaymentFailed`)
  3. Reservation Service: 예매 취소
  4. Event Service: 좌석 선점 해제

**관련 요구사항**: REQ-PAY-011 (SAGA 패턴), REQ-PAY-012 (보상 트랜잭션)

#### 1.4.2 SAGA vs 2PC 선택 이유
- **2PC (Two-Phase Commit)**: 동기식, 블로킹, 가용성 저하
- **SAGA**: 비동기식, 보상 트랜잭션, 가용성 우선
- **선택 근거**: 티켓팅 시스템은 성능/가용성이 중요하므로 SAGA 선택

### 1.5 Transactional Outbox 패턴

#### 1.5.1 적용 배경
- **문제점**: 서비스 상태 변경 후 Kafka 발행 전 장애 발생 시 이벤트 유실
- **해결책**: DB 트랜잭션 내 Outbox 테이블에 이벤트 저장 → Poller/CDC로 Kafka 발행

#### 1.5.2 구현 방안
1. **Outbox 테이블 설계** (`common.outbox_events`):
   ```sql
   CREATE TABLE outbox_events (
     id UUID PRIMARY KEY,
     aggregate_type VARCHAR(50),    -- 'Reservation', 'Payment'
     aggregate_id UUID,
     event_type VARCHAR(100),       -- 'PaymentSuccess', 'ReservationCancelled' 등
     payload JSONB,
     created_at TIMESTAMP,
     published BOOLEAN DEFAULT FALSE,
     published_at TIMESTAMP,
     retry_count INT DEFAULT 0,
     last_error TEXT
   );
   ```

2. **이벤트 발행 프로세스**:
   - 비즈니스 로직 실행 + Outbox INSERT (동일 트랜잭션)
   - Poller (Spring @Scheduled) 또는 CDC (Debezium)가 주기적으로 Outbox 읽어 Kafka 발행
   - 발행 성공 시 `published = TRUE` 업데이트

**관련 요구사항**: REQ-RSV-012 (Transactional Outbox Pattern), REQ-PAY-013 (Kafka 이벤트 발행)

### 1.6 Circuit Breaker 패턴

#### 1.6.1 적용 위치
- **API Gateway**: 다운스트림 서비스 호출 시
- **Payment Service**: PortOne API 호출 시
- **Reservation Service**: Feign 클라이언트로 Queue Service 호출 시

#### 1.6.2 Resilience4j 설정 예시
```yaml
resilience4j.circuitbreaker:
  configs:
    default:
      slidingWindowSize: 100           # 최근 100개 요청 기준
      failureRateThreshold: 50         # 50% 이상 실패 시 Circuit Open
      waitDurationInOpenState: 60s     # Open 상태 60초 유지
      permittedNumberOfCallsInHalfOpenState: 10
  instances:
    paymentService:
      baseConfig: default
      waitDurationInOpenState: 60s     # Payment는 대기 시간 60초
```

**관련 요구사항**: REQ-GW-007 (Circuit Breaker 통합), REQ-PAY-009 (Circuit Breaker)

### 1.7 API Gateway 패턴

#### 1.7.1 책임
- **라우팅**: Path 기반으로 5개 서비스로 라우팅
- **인증**: JWT 토큰 검증
- **Rate Limiting**: IP/사용자 기반 제한
- **보안 헤더**: CORS, XSS 방지, HSTS
- **Circuit Breaker**: 다운스트림 장애 격리

**관련 요구사항**: REQ-GW-001 ~ REQ-GW-018 (18개)

### 1.8 CQRS 패턴 적용

- **Event Service**: Redis 캐싱으로 조회 성능 최적화 (CQRS 유사)
  - 쓰기: RDB (PostgreSQL)
  - 읽기: Redis Cache + RDB Fallback