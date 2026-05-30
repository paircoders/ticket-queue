## 06. Payment Service (PortOne/SAGA/보상)

> **영역 범위**: Payment Service의 결제 생성(POST /payments), 결제 승인(POST /payments/confirm), 조회 API, PortOne 연동, SAGA Orchestration, 보상 트랜잭션, Outbox 발행, 멱등성, 내부 API 보안, DLQ 전략 전반
> **사전 준비**: `cd docker && docker-compose up -d` (PostgreSQL 5432, Valkey 6379, Kafka 9092 기동), `./gradlew :payment-service:bootRun` 및 `./gradlew :reservation-service:bootRun` 기동, PortOne 테스트 모드 storeId/channelKey 설정 확인 (`external.portone.*`), `INTERNAL_API_KEY=local-dev-internal-api-key` 환경변수 설정, 테스트 전 `payment_service.payments`, `common.outbox_events`, `common.processed_events` 테이블 비우기
> **주 실행 수단**: curl REST + gradle test/integrationTest, PortOne 테스트모드/Outbox/SAGA 상태 검증
> **총 항목 수**: 26

---

### TC-PAY-001 — 정상 결제 생성: PENDING 저장 및 PortOne pre-register 호출

- [ ] 미실행
- **관련 REQ**: REQ-PAY-006, REQ-PAY-007
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: Reservation Service에 `reservationId=<UUID>`, `status=PENDING`, `totalAmount=300000`, `holdExpiresAt=+5분`, `userId=<userId>` 인 예매 레코드 존재. PortOne 테스트 모드 설정 완료.
- **실행 단계**:
  1. `POST /payments` 호출 (Gateway 통해 JWT 포함, X-User-Id 주입됨):
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","amount":300000,"paymentMethod":"CARD"}'
     ```
  2. 응답 본문에서 `paymentId`, `paymentKey`, `storeId`, `channelKey` 확인
  3. DB 확인: `SELECT id, status, payment_key, amount FROM payment_service.payments WHERE reservation_id='<UUID>';`
- **기대 결과**: HTTP 200, 응답에 `paymentId`/`paymentKey`/`storeId`/`channelKey`/`amount` 포함. DB row status=PENDING, amount=300000
- **검증 포인트**: DB `payment_service.payments` row 1건, status=`PENDING`. `common.outbox_events` row 없음 (createPayment 정상 흐름에서는 Outbox 미발행).

---

### TC-PAY-002 — 결제 승인 정상 흐름: PortOne PAID → SUCCESS + Outbox PaymentSuccess

- [ ] 미실행
- **관련 REQ**: REQ-PAY-010, REQ-PAY-011, REQ-PAY-013
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: TC-PAY-001 완료로 `paymentId`, `paymentKey` 확보. PortOne 테스트 모드에서 해당 `paymentKey`로 결제 완료하여 `transactionId` 획득. `holdExpiresAt` 미경과.
- **실행 단계**:
  1. PortOne SDK(또는 테스트 모드 직접 호출)로 결제 완료 후 `transactionId` 확보
  2. `POST /payments/confirm` 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments/confirm \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","paymentId":"<paymentId>","paymentKey":"<paymentKey>","transactionId":"<txId>","amount":300000}'
     ```
  3. DB 확인: `SELECT status, portone_transaction_id, paid_at FROM payment_service.payments WHERE id='<paymentId>';`
  4. Outbox 확인: `SELECT aggregate_type, event_type, published, payload FROM common.outbox_events WHERE aggregate_id='<paymentId>';`
- **기대 결과**: HTTP 200, `status=SUCCESS`, `paidAt` 비어있지 않음. DB status=SUCCESS, portone_transaction_id 채워짐. outbox row 1건: `event_type=PaymentSuccess`, `published=false`
- **검증 포인트**: outbox payload JSON의 `causationId=null` (SAGA root 컨벤션), `reservationId`, `seatIds`, `scheduleId`, `portoneTransactionId` 모두 채워짐.

---

### TC-PAY-003 — holdExpiresAt 경과 후 결제 생성 거부

- [ ] 미실행
- **관련 REQ**: REQ-PAY-005
- **분류**: 엣지, 경계값
- **우선순위**: P0
- **사전조건**: Reservation Service에 `holdExpiresAt=과거 1초 전(UTC)`, `status=PENDING` 인 예매 레코드 존재.
- **실행 단계**:
  1. `POST /payments` 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<expiredReservationId>","amount":300000,"paymentMethod":"CARD"}'
     ```
  2. 응답 코드 및 `code` 필드 확인
- **기대 결과**: HTTP 410, `{"code":"HOLD_EXPIRED"}`
- **검증 포인트**: DB `payment_service.payments`에 row 미생성. `common.outbox_events`에 row 없음.

---

### TC-PAY-004 — holdExpiresAt 정확한 경계값(now == expiresAt)에서 거부

- [ ] 미실행
- **관련 REQ**: REQ-PAY-005
- **분류**: 경계값
- **우선순위**: P1
- **사전조건**: 현재 UTC 시각과 정확히 동일한 `holdExpiresAt`을 가진 예매 레코드 준비. (테스트 시간 오차 최소화를 위해 integrationTest로 실행)
- **실행 단계**:
  1. `./gradlew :payment-service:integrationTest --tests "*holdExpiredAtExactBoundary*"` 실행
  2. 또는 직접: Reservation stub `holdExpiresAt = LocalDateTime.now(ZoneOffset.UTC)` 설정 후 `POST /payments` 호출
- **기대 결과**: `HOLD_EXPIRED` 예외 발생 (isBefore 판정: now.isBefore(expiresAt)=false이므로 거부)
- **검증 포인트**: `LocalDateTime.now(ZoneOffset.UTC).isBefore(holdExpiresAt)` 이 false일 때 `ErrorCode.HOLD_EXPIRED` 반환.

---

### TC-PAY-005 — Queue Token 만료 후에도 hold_expires_at 미경과 예매는 결제 가능

- [ ] 미실행
- **관련 REQ**: REQ-PAY-005
- **분류**: 엣지
- **우선순위**: P0
- **사전조건**: Queue Token은 만료됐으나 예매의 `holdExpiresAt`은 미래(+2분), `status=PENDING` 인 예매 레코드 존재.
- **실행 단계**:
  1. Queue Token이 만료된 상태에서 (Redis의 Queue approved set에서 제거됨) 동일 JWT로 `POST /payments` 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","amount":300000,"paymentMethod":"CARD"}'
     ```
  2. 응답 코드 확인
- **기대 결과**: HTTP 200 (결제 생성 성공). Payment Service는 Queue Token 검증을 수행하지 않으며 Reservation의 `PENDING + holdExpiresAt 미경과` 여부만 검증.
- **검증 포인트**: DB `payment_service.payments` row 생성, status=PENDING.

---

### TC-PAY-006 — 예매 상태 CONFIRMED/CANCELLED인 경우 결제 생성 거부

- [ ] 미실행
- **관련 REQ**: REQ-PAY-005
- **분류**: 예외
- **우선순위**: P0
- **사전조건**: `status=CONFIRMED` 인 예매, `status=CANCELLED` 인 예매 각각 준비.
- **실행 단계**:
  1. CONFIRMED 예매로 `POST /payments` 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<confirmedReservationId>","amount":300000,"paymentMethod":"CARD"}'
     ```
  2. CANCELLED 예매로 동일 호출
  3. 각 응답 코드, `code` 필드 확인
- **기대 결과**: 두 경우 모두 HTTP 422, `{"code":"RESERVATION_NOT_PAYABLE"}`
- **검증 포인트**: `validateReservation()` 내 `reservation.status != ReservationStatus.PENDING` 분기. HOLD_EXPIRED(410)와 다른 에러코드임을 확인.

---

### TC-PAY-007 — 요청 금액과 예매 금액 불일치 시 결제 생성 거부 (위변조 방지)

- [ ] 미실행
- **관련 REQ**: REQ-PAY-005, REQ-PAY-007
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: `totalAmount=300000` 인 예매 레코드 존재.
- **실행 단계**:
  1. 클라이언트가 `amount=1` 로 위변조하여 `POST /payments` 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","amount":1,"paymentMethod":"CARD"}'
     ```
- **기대 결과**: HTTP 400, `{"code":"PAYMENT_AMOUNT_MISMATCH"}`
- **검증 포인트**: DB row 미생성. PortOne pre-register 미호출.

---

### TC-PAY-008 — PortOne 결제 승인 시 금액 위변조 거부 (AMOUNT_MISMATCH)

- [ ] 미실행
- **관련 REQ**: REQ-PAY-010
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: PENDING Payment row 존재, `paymentKey` 확보. PortOne 테스트 모드에서 해당 paymentKey로 금액을 1원으로 변조한 결제 시도 후 transactionId 확보 (또는 통합 테스트에서 stub 활용).
- **실행 단계**:
  1. `POST /payments/confirm` 호출, `amount=300000` 이지만 PortOne 응답의 `amount.total=1`:
     ```bash
     ./gradlew :payment-service:integrationTest --tests "*confirmAmountTampered*"
     ```
  2. DB 확인: `SELECT status, failure_reason FROM payment_service.payments WHERE id='<paymentId>';`
- **기대 결과**: HTTP 200, `status=FAILED`. DB `failure_reason=AMOUNT_MISMATCH`. Outbox row 1건: `event_type=PaymentFailed`.
- **검증 포인트**: `failureReason="AMOUNT_MISMATCH"`, `outbox_events.event_type=PaymentFailed`, `published=false`.

---

### TC-PAY-009 — PortOne transactionId 위변조 거부 (TX_ID_MISMATCH)

- [ ] 미실행
- **관련 REQ**: REQ-PAY-010
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: PENDING Payment row 존재. PortOne은 정상 PAID 응답 반환.
- **실행 단계**:
  1. PortOne이 반환한 `transactionId` 와 다른 값을 confirm 요청에 포함:
     ```bash
     curl -s -X POST http://localhost:8080/payments/confirm \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","paymentId":"<paymentId>","paymentKey":"<paymentKey>","transactionId":"tampered-tx-id","amount":300000}'
     ```
  2. DB 확인: `SELECT status, failure_reason FROM payment_service.payments WHERE id='<paymentId>';`
- **기대 결과**: HTTP 200, `status=FAILED`, `failure_reason=TX_ID_MISMATCH`. Outbox PaymentFailed row 생성.
- **검증 포인트**: `payment.failureReason="TX_ID_MISMATCH"`. Reservation이 PaymentFailed를 수신하여 보상 체인 진입.

---

### TC-PAY-010 — confirm 요청의 paymentKey 위변조 거부 (INVALID_INPUT)

- [ ] 미실행
- **관련 REQ**: REQ-PAY-004, REQ-PAY-010
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: PENDING Payment row 존재, `paymentKey` 확보.
- **실행 단계**:
  1. DB paymentKey와 다른 값으로 `POST /payments/confirm` 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments/confirm \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","paymentId":"<paymentId>","paymentKey":"forged-key","transactionId":"tx-1","amount":300000}'
     ```
- **기대 결과**: HTTP 400, `{"code":"INVALID_INPUT"}`
- **검증 포인트**: PortOne 호출 미발생. DB status 변경 없음. Outbox row 미생성.

---

### TC-PAY-011 — 동일 reservationId 중복 결제 시도 방지 (PAYMENT_ALREADY_EXISTS)

- [ ] 미실행
- **관련 REQ**: REQ-PAY-004, REQ-PAY-006
- **분류**: 멱등성
- **우선순위**: P0
- **사전조건**: 동일 reservationId로 첫 번째 결제 생성 완료(PENDING 상태).
- **실행 단계**:
  1. 동일 `reservationId`로 `POST /payments` 두 번째 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<sameUUID>","amount":300000,"paymentMethod":"CARD"}'
     ```
  2. 응답 코드 및 `code` 필드 확인
  3. DB 확인: row 수 확인 `SELECT COUNT(*) FROM payment_service.payments WHERE reservation_id='<UUID>';`
- **기대 결과**: HTTP 409, `{"code":"PAYMENT_ALREADY_EXISTS"}`. DB row는 1건 유지.
- **검증 포인트**: `existsByReservationIdAndStatusIn(reservationId, [PENDING, SUCCESS])=true` 분기 동작. 두 번째 요청에서 Reservation 조회, PortOne 호출 미발생.

---

### TC-PAY-012 — confirm 멱등성: SUCCESS 상태 결제 재승인 시도

- [ ] 미실행
- **관련 REQ**: REQ-PAY-004, REQ-PAY-010
- **분류**: 멱등성
- **우선순위**: P0
- **사전조건**: 결제 승인 완료(status=SUCCESS) 상태의 Payment row 존재.
- **실행 단계**:
  1. 이미 SUCCESS인 paymentId로 `POST /payments/confirm` 재호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments/confirm \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","paymentId":"<successPaymentId>","paymentKey":"<paymentKey>","transactionId":"<txId>","amount":300000}'
     ```
- **기대 결과**: HTTP 409, `{"code":"PAYMENT_ALREADY_EXISTS"}`
- **검증 포인트**: PortOne `getPayment` 호출 미발생. Outbox row 추가 미생성. DB status 변경 없음.

---

### TC-PAY-013 — 동시 confirm 경쟁 조건: PESSIMISTIC_WRITE 락으로 중복 Outbox 방지

- [ ] 미실행
- **관련 REQ**: REQ-PAY-004, REQ-PAY-010
- **분류**: 동시성
- **우선순위**: P0
- **사전조건**: PENDING Payment row 1건. PortOne 응답 지연 stub 설정 (응답 전 다른 스레드가 SELECT FOR UPDATE 획득).
- **실행 단계**:
  1. 동일 paymentId로 동시에 2개 스레드에서 `POST /payments/confirm` 호출:
     ```kotlin
     // integrationTest 내 Thread.sleep 없이 parallel 호출
     val results = (1..2).map {
         CompletableFuture.supplyAsync { postConfirm(paymentId) }
     }.map { it.get() }
     ```
  2. DB 확인: `SELECT COUNT(*) FROM common.outbox_events WHERE aggregate_id='<paymentId>';`
- **기대 결과**: 한 요청은 200 + SUCCESS, 다른 요청은 409 + PAYMENT_ALREADY_EXISTS. outbox row는 정확히 1건.
- **검증 포인트**: `paymentRepository.findByIdForUpdate()` 락 재검증으로 두 번째 스레드가 이미 SUCCESS 상태를 확인 후 거부. Outbox PaymentSuccess row 중복 없음.

---

### TC-PAY-014 — SAGA 보상 체인: PortOne FAILED 응답 → PaymentFailed → 예매 CANCELLED → 좌석 AVAILABLE

- [ ] 미실행
- **관련 REQ**: REQ-PAY-011, REQ-PAY-012, REQ-PAY-013, REQ-RSV-011
- **분류**: 보상트랜잭션
- **우선순위**: P0
- **사전조건**: 결제 생성 완료(PENDING). PortOne 테스트 모드에서 FAILED 응답 반환하도록 설정. Reservation Service, Event Service 기동 및 Kafka Consumer 활성화.
- **실행 단계**:
  1. PortOne 테스트 모드에서 결제 실패 시나리오로 transactionId 확보
  2. `POST /payments/confirm` 호출 (PortOne이 FAILED 반환하는 paymentKey 사용)
  3. Outbox Poller가 `payment.events` 토픽으로 PaymentFailed 발행 (최대 1~2초 대기)
  4. Reservation Service Consumer 수신 확인: `SELECT status FROM reservation_service.reservations WHERE id='<reservationId>';`
  5. Event Service Consumer 수신 후 좌석 상태 확인: `SELECT status FROM event_service.seats WHERE id IN (<seatIds>);`
- **기대 결과**: Payment status=FAILED. Reservation status=CANCELLED. 좌석 status=AVAILABLE.
- **검증 포인트**: `common.outbox_events` PaymentFailed row `published=true`. Reservation outbox의 ReservationCancelled 이벤트 발행. 좌석 DB AVAILABLE 복원.

---

### TC-PAY-015 — SAGA 보상 멱등성: PaymentFailed 이벤트 중복 수신 시 Reservation 한 번만 CANCELLED

- [ ] 미실행
- **관련 REQ**: REQ-PAY-012
- **분류**: 멱등성, 보상트랜잭션
- **우선순위**: P0
- **사전조건**: Kafka 토픽 `payment.events`에 동일 `eventId`의 PaymentFailed 메시지 2건 존재 (수동 produce 또는 재시도 시뮬레이션). `reservation-payment-consumer` Consumer Group.
- **실행 단계**:
  1. 동일 `eventId`를 가진 PaymentFailed 이벤트를 `payment.events` 토픽에 2회 produce:
     ```bash
     # Kafka CLI로 동일 payload 2번 전송
     kafka-console-producer.sh --topic payment.events --bootstrap-server localhost:9092
     ```
  2. Reservation Service Consumer 로그에서 두 번째 수신 처리 확인
  3. DB 확인: `SELECT COUNT(*) FROM common.processed_events WHERE event_id='<eventId>' AND consumer_service='reservation-service';`
- **기대 결과**: `processed_events` row 1건. Reservation status=CANCELLED (한 번만 전이). 두 번째 수신은 `DataIntegrityViolationException` 으로 중복 감지 후 스킵.
- **검증 포인트**: `IdempotentConsumerTemplate.process()` 내 `tryRecord()` 중복 INSERT 시도 → `DataIntegrityViolationException` → `isNew=false` → ack 후 반환. DB Reservation 상태 변경 1회.

---

### TC-PAY-016 — Outbox 패턴: 결제 상태 변경과 outbox INSERT 원자성 검증

- [ ] 미실행
- **관련 REQ**: REQ-PAY-013, REQ-RSV-012
- **분류**: 정상, 멱등성
- **우선순위**: P0
- **사전조건**: PENDING Payment row 존재.
- **실행 단계**:
  1. `POST /payments/confirm` 로 PAID 결제 승인
  2. 응답 직후 즉시 DB 조회:
     ```sql
     SELECT p.status, o.event_type, o.published
     FROM payment_service.payments p
     LEFT JOIN common.outbox_events o ON o.aggregate_id = p.id
     WHERE p.id = '<paymentId>';
     ```
  3. outbox row의 `id`와 payload의 `eventId` 일치 여부 확인:
     ```sql
     SELECT id, payload->>'eventId' AS payload_event_id FROM common.outbox_events WHERE aggregate_id='<paymentId>';
     ```
- **기대 결과**: `p.status=SUCCESS` 와 `o.event_type=PaymentSuccess` 가 동일 트랜잭션으로 존재. `o.published=false` (폴러 미실행 상태). `o.id == payload.eventId` (SOT 계약).
- **검증 포인트**: Outbox row `id = payload.eventId`. `published=false`. `aggregate_type=Payment`.

---

### TC-PAY-017 — Outbox Poller: 미발행 이벤트 1초 내 Kafka 발행 후 published=true 마킹

- [ ] 미실행
- **관련 REQ**: REQ-PAY-013
- **분류**: 정상
- **우선순위**: P1
- **사전조건**: Outbox Poller 활성화 (`outbox.poller.enabled=true`). Kafka 기동. `outbox_events` 에 PaymentSuccess row published=false 존재.
- **실행 단계**:
  1. 결제 승인 완료 직후 1초 대기
  2. DB 확인: `SELECT published, published_at FROM common.outbox_events WHERE aggregate_id='<paymentId>';`
  3. Kafka `payment.events` 토픽 확인:
     ```bash
     kafka-console-consumer.sh --topic payment.events --bootstrap-server localhost:9092 --from-beginning --max-messages 1
     ```
- **기대 결과**: 1초 이내 `published=true`, `published_at` 채워짐. Kafka 메시지에 `eventType=PaymentSuccess` header, payload에 `reservationId`, `seatIds`, `amount` 포함.
- **검증 포인트**: Kafka 메시지 header `eventType`, `aggregateType`. payload `eventId` == outbox row `id`.

---

### TC-PAY-018 — Outbox Poller 재시도: Kafka 발행 실패 시 retryCount 증가 후 3회 초과 DLQ 이동

- [ ] 미실행
- **관련 REQ**: REQ-PAY-013
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: Kafka broker를 일시 중단하거나 네트워크 차단으로 발행 실패 시뮬레이션. `outbox.poller.max-retry-count=3` 설정 확인.
- **실행 단계**:
  1. 결제 승인 완료 후 Kafka broker 중단: `docker stop kafka`
  2. 3초 이상 대기 (1초 폴링 3회)
  3. DB 확인: `SELECT retry_count, last_error, published FROM common.outbox_events WHERE aggregate_id='<paymentId>';`
  4. Kafka broker 재기동: `docker start kafka`
  5. DLQ 확인: `kafka-console-consumer.sh --topic dlq.payment --bootstrap-server localhost:9092 --from-beginning --max-messages 5`
- **기대 결과**: `retry_count=3`, `published=true`, `last_error` 에 예외 메시지 기록. `dlq.payment` 토픽에 해당 메시지 발행. Kafka header에 `dlqReason=MAX_RETRY_EXCEEDED`.
- **검증 포인트**: `outbox_events.retry_count >= maxRetryCount` 도달 시 DLQ 이동. `published=true`로 재폴링 제외.

---

### TC-PAY-019 — PortOne pre-register 실패 시 Payment FAILED + Outbox PaymentFailed 원자적 저장

- [ ] 미실행
- **관련 REQ**: REQ-PAY-007, REQ-PAY-012
- **분류**: 예외
- **우선순위**: P0
- **사전조건**: PortOne API 응답이 5xx 또는 네트워크 오류 반환하도록 설정 (테스트 환경에서는 integrationTest 활용).
- **실행 단계**:
  1. `./gradlew :payment-service:integrationTest --tests "*portoneFailureInsertsOutboxRowAtomically*"` 실행
  2. DB 확인:
     ```sql
     SELECT p.status, o.event_type, o.published
     FROM payment_service.payments p
     JOIN common.outbox_events o ON o.aggregate_id = p.id
     WHERE p.reservation_id = '<reservationId>';
     ```
- **기대 결과**: HTTP 502, `{"code":"PORTONE_PRE_REGISTER_FAILED"}`. DB `payment.status=FAILED`. Outbox `event_type=PaymentFailed`, `published=false`.
- **검증 포인트**: Payment FAILED 저장과 Outbox INSERT가 동일 `transactionTemplate.executeWithoutResult` 블록에서 원자적 커밋. DB 상태 불일치 없음.

---

### TC-PAY-020 — PortOne Circuit Breaker Open: 503 반환, Payment PENDING 유지

- [ ] 미실행
- **관련 REQ**: REQ-PAY-009
- **분류**: 예외
- **우선순위**: P0
- **사전조건**: Resilience4j Circuit Breaker가 OPEN 상태 (PortOne 연속 실패 임계값 초과 또는 테스트 강제 설정). PENDING Payment row 존재.
- **실행 단계**:
  1. `POST /payments/confirm` 호출 (CB OPEN 상태):
     ```bash
     curl -s -X POST http://localhost:8080/payments/confirm \
       -H "Authorization: Bearer <accessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","paymentId":"<paymentId>","paymentKey":"<paymentKey>","transactionId":"tx-1","amount":300000}'
     ```
  2. DB 확인: `SELECT status FROM payment_service.payments WHERE id='<paymentId>';`
- **기대 결과**: HTTP 503, `{"code":"PORTONE_CIRCUIT_OPEN"}`. DB `payment.status=PENDING` 유지 (클라이언트 재시도 가능 상태).
- **검증 포인트**: `PortoneFallbackFactory` 에서 `CallNotPermittedException → PortoneCircuitOpenException` 변환. Outbox row 미생성. `findByIdForUpdate` 미호출.

---

### TC-PAY-021 — PortOne CircuitBreaker Open: pre-register 실패 시 markFailed + PaymentFailed Outbox 발행 후 503

- [ ] 미실행
- **관련 REQ**: REQ-PAY-007, REQ-PAY-009
- **분류**: 예외, 보상트랜잭션
- **우선순위**: P0
- **사전조건**: PortOne CB OPEN 상태. 정상 Reservation PENDING 예매 존재.
- **실행 단계**:
  1. `POST /payments` 호출 (createPayment, CB OPEN):
     ```bash
     ./gradlew :payment-service:integrationTest --tests "*portoneCircuitBreakerOpen*"
     ```
  2. DB 확인:
     ```sql
     SELECT p.status, p.failure_reason, o.event_type
     FROM payment_service.payments p
     LEFT JOIN common.outbox_events o ON o.aggregate_id = p.id;
     ```
- **기대 결과**: HTTP 503 `PORTONE_CIRCUIT_OPEN`. `payment.status=FAILED`, `failure_reason="PortOne circuit breaker open"`. Outbox `event_type=PaymentFailed` row 존재.
- **검증 포인트**: createPayment CB 분기에서 markFailed + recordPaymentFailed 가 트랜잭션 내 원자적 처리. 보상 체인(SAGA) 진입.

---

### TC-PAY-022 — 내부 API 보안: X-Service-Api-Key 누락 시 /internal/reservations 401 반환

- [ ] 미실행
- **관련 REQ**: 해당 없음 (CLAUDE.md 원칙: 내부 API 보안)
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: Reservation Service 기동. `INTERNAL_API_KEY` 환경변수 설정됨.
- **실행 단계**:
  1. `X-Service-Api-Key` 헤더 없이 `/internal/reservations/{id}` 직접 호출:
     ```bash
     curl -s -X GET http://localhost:8084/internal/reservations/<reservationId>
     ```
  2. 잘못된 키로 호출:
     ```bash
     curl -s -X GET http://localhost:8084/internal/reservations/<reservationId> \
       -H "X-Service-Api-Key: wrong-key"
     ```
  3. 올바른 키로 호출:
     ```bash
     curl -s -X GET http://localhost:8084/internal/reservations/<reservationId> \
       -H "X-Service-Api-Key: local-dev-internal-api-key"
     ```
- **기대 결과**: 누락/오류 키 → HTTP 401, `{"code":"INTERNAL_API_UNAUTHORIZED"}`. 올바른 키 → HTTP 200.
- **검증 포인트**: `InternalApiAuthInterceptor` 필터 동작. Gateway에서 `/internal/**` 경로 외부 차단 확인 (Gateway 로그에서 route 미매칭).

---

### TC-PAY-023 — 카드 정보 마스킹: 결제 상세 조회 시 cardNumber 마스킹 형식 검증

- [ ] 미실행
- **관련 REQ**: REQ-PAY-014
- **분류**: 보안
- **우선순위**: P1
- **사전조건**: SUCCESS 상태 Payment row 존재, `portone_response` JSONB에 `{"method":{"card":{"publisher":"SHINHAN","number":"1234-5678-9012-3456"}}}` 포함. 실제 환경에서는 PortOne이 마스킹된 번호를 반환하나 테스트에서는 저장된 값 기준 검증.
- **실행 단계**:
  1. `GET /payments/{paymentId}` 호출:
     ```bash
     curl -s http://localhost:8080/payments/<paymentId> \
       -H "Authorization: Bearer <accessToken>"
     ```
  2. 응답의 `cardName`, `cardNumber` 필드 확인
- **기대 결과**: `cardName=SHINHAN`, `cardNumber` 값이 응답에 포함. FAILED/PENDING 상태 결제는 `cardName=null`, `cardNumber=null`.
- **검증 포인트**: `PaymentMaskingMapper.extract()` 가 `portone_response` JSONB의 `method.card.publisher`, `method.card.number` 를 추출. portoneResponse=null 시 null 반환.

---

### TC-PAY-024 — 타인의 결제 조회/승인 시도 403 차단

- [ ] 미실행
- **관련 REQ**: REQ-PAY-014
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: userId=A 의 Payment row 존재. userId=B 의 JWT 준비.
- **실행 단계**:
  1. userId=B JWT로 userId=A의 paymentId에 `GET /payments/{paymentId}` 호출:
     ```bash
     curl -s http://localhost:8080/payments/<userAPaymentId> \
       -H "Authorization: Bearer <userBAccessToken>"
     ```
  2. userId=B JWT로 userId=A의 paymentId에 `POST /payments/confirm` 호출:
     ```bash
     curl -s -X POST http://localhost:8080/payments/confirm \
       -H "Authorization: Bearer <userBAccessToken>" \
       -H "Content-Type: application/json" \
       -d '{"reservationId":"<UUID>","paymentId":"<userAPaymentId>","paymentKey":"<key>","transactionId":"tx","amount":300000}'
     ```
- **기대 결과**: 두 경우 모두 HTTP 403, `{"code":"FORBIDDEN"}`
- **검증 포인트**: `payment.userId != userId` 검사. DB 상태 변경 없음. Outbox row 미생성.

---

### TC-PAY-025 — SAGA 정방향: PaymentSuccess → Reservation CONFIRMED → 좌석 SOLD 엔드투엔드

- [ ] 미실행
- **관련 REQ**: REQ-PAY-011, REQ-RSV-004
- **분류**: 정상, 보상트랜잭션
- **우선순위**: P0
- **사전조건**: Reservation Service, Event Service, Payment Service 모두 기동. Kafka Consumer 활성화. 좌석 status=HOLD 상태.
- **실행 단계**:
  1. 결제 생성 (`POST /payments`) 및 PortOne 테스트 결제 완료 후 transactionId 획득
  2. 결제 승인 (`POST /payments/confirm`) 호출
  3. Outbox Poller가 PaymentSuccess 발행 대기 (최대 2초)
  4. Reservation 상태 확인:
     ```sql
     SELECT status, ticket_number FROM reservation_service.reservations WHERE id='<reservationId>';
     ```
  5. 좌석 상태 확인:
     ```sql
     SELECT status FROM event_service.seats WHERE id IN (<seatIds>);
     ```
- **기대 결과**: `reservation.status=CONFIRMED`, `ticket_number` 채워짐. `seats.status=SOLD`.
- **검증 포인트**: `reservation-payment-consumer` 가 PaymentSuccess 수신 후 예매 확정. `event-payment-consumer` 가 좌석 SOLD 처리. `processed_events` 에 각 Consumer별 row 존재.

---

### TC-PAY-026 — DLQ 분류: IllegalArgumentException/JsonProcessingException 즉시 DLQ, TimeoutException 재시도

- [ ] 미실행
- **관련 REQ**: REQ-PAY-013
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: Kafka Consumer 활성화. `payment.events` 토픽에 수동으로 잘못된 JSON 메시지 produce.
- **실행 단계**:
  1. 잘못된 JSON payload를 `payment.events` 토픽에 produce:
     ```bash
     echo '{"invalid":"not-a-payment-event"}' | kafka-console-producer.sh \
       --topic payment.events --bootstrap-server localhost:9092
     ```
  2. Consumer 로그에서 `JsonProcessingException` 발생 및 DLQ 이동 확인
  3. `dlq.payment` 토픽 메시지 확인:
     ```bash
     kafka-console-consumer.sh --topic dlq.payment --bootstrap-server localhost:9092 --from-beginning
     ```
  4. `TimeoutException` 시뮬레이션: DB 응답 지연 유발 후 Consumer 재시도 횟수 확인
- **기대 결과**: `JsonProcessingException` → 재시도 없이 즉시 `dlq.payment` 이동. `TimeoutException` → 지수 백오프 3회 재시도 후 DLQ. `ExceptionClassifier.isRetryable()` 분류 로직 동작 확인.
- **검증 포인트**: `ExceptionClassifier.nonRetryableExceptions` 에 `JsonProcessingException` 포함 여부. 재시도 불가 예외 시 `processed_events` row 즉시 삭제 후 DLQ. 재시도 가능 예외 시 `retry_count` 증가.
