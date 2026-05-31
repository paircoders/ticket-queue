## 05. Reservation Service (분산락/선점/Outbox)

> **영역 범위**: Reservation Service의 좌석 선점(Redisson 분산락), 예매 생성/취소, Outbox 패턴 이벤트 발행, Kafka Consumer 멱등성, hold 만료 배치, 내부 API 보안 전체
> **사전 준비**: `cd docker && docker-compose up -d` 로 PostgreSQL(5432), Valkey/Redis(6379), Kafka(9092) 기동; `reservation-service` bootRun; 유효한 JWT(X-User-Id 주입), 유효한 Queue Token(`queue:token:{token}` Redis SET) 사전 삽입; 공연·회차·좌석 데이터 Event Service에 등록(scheduleId, seatId UUID 확보)
> **주 실행 수단**: curl REST + gradle integrationTest, Redis CLI, psql 직접 쿼리(reservation_service/common 스키마), Kafka consumer-groups 및 topic describe
> **총 항목 수**: 22

---

### TC-RSV-001 — 정상 좌석 선점: PENDING 예매 생성 및 Redis SET 갱신

- [x] 통과 (2026-05-31, 근거: HTTP 201, status=PENDING, hold_expires_at=now+5분, hold_seats SET에 두 좌석 UUID 존재 확인)
- **관련 REQ**: REQ-RSV-001, REQ-RSV-008
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: AVAILABLE 좌석 2개(seatId-A, seatId-B), 유효한 Queue Token(`queue:token:qr_test01`) Redis에 SET, 유저 userId-1
- **실행 단계**:
  1. Redis에 Queue Token 삽입
     ```bash
     redis-cli SET queue:token:qr_test01 '{"userId":"<userId-1>","scheduleId":"<scheduleId>"}' EX 600
     ```
  2. 좌석 선점 요청
     ```bash
     curl -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-1>" \
       -H "X-Queue-Token: qr_test01" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId>","seatIds":["<seatId-A>","<seatId-B>"]}'
     ```
  3. DB 확인
     ```sql
     SELECT id, status, hold_expires_at FROM reservation_service.reservations
     WHERE user_id = '<userId-1>' ORDER BY created_at DESC LIMIT 1;
     ```
  4. Redis SET 확인
     ```bash
     redis-cli SMEMBERS hold_seats:<scheduleId>
     ```
- **기대 결과**: HTTP 201, 응답 `status=PENDING`, `holdExpiresAt` ≈ now+5분; DB row `status='PENDING'`, `hold_expires_at` 설정; Redis `hold_seats:<scheduleId>` SET에 seatId-A, seatId-B 포함
- **검증 포인트**: HTTP 상태 코드 201 / DB `reservations.status='PENDING'` / `hold_expires_at` 값이 현재+5분 ±5초 이내 / Redis SET `hold_seats:<scheduleId>`에 두 좌석 UUID 존재

---

### TC-RSV-002 — 동시 선점 경쟁: 동일 좌석에 N명 동시 요청 시 1명만 성공

- [x] 통과 (2026-05-31, 근거: 10명 동시 요청 중 정확히 1개 HTTP 201, 9개 HTTP 409(SEAT_ALREADY_HELD))
- **관련 REQ**: REQ-RSV-001
- **분류**: 동시성
- **우선순위**: P0
- **사전조건**: AVAILABLE 좌석 1개(seatId-X), 유효 Queue Token 10개(qr_c01~qr_c10) 각각 다른 userId로 Redis에 SET
- **실행 단계**:
  1. 10개 Queue Token Redis 삽입
     ```bash
     for i in $(seq 1 10); do
       redis-cli SET queue:token:qr_c0${i} "{\"userId\":\"user-c0${i}\",\"scheduleId\":\"<scheduleId>\"}" EX 600
     done
     ```
  2. 10개 curl 요청을 백그라운드로 동시 발사
     ```bash
     for i in $(seq 1 10); do
       curl -s -o /tmp/rsv_c${i}.json -w "%{http_code}" \
         -X POST http://localhost:8084/reservations/hold \
         -H "X-User-Id: user-c0${i}" \
         -H "X-Queue-Token: qr_c0${i}" \
         -H "Content-Type: application/json" \
         -d "{\"scheduleId\":\"<scheduleId>\",\"seatIds\":[\"<seatId-X>\"]}" &
     done; wait
     ```
  3. 응답 코드 집계
     ```bash
     grep -c "201" /tmp/rsv_c*.json || echo "201 count from status codes"
     ```
  4. DB 중복 확인
     ```sql
     SELECT COUNT(*) FROM reservation_service.reservations r
     JOIN reservation_service.reservation_seats rs ON r.id = rs.reservation_id
     WHERE rs.seat_id = '<seatId-X>' AND r.status = 'PENDING';
     ```
- **기대 결과**: 정확히 1개 요청만 HTTP 201 반환; 나머지 9개는 HTTP 409(`SEAT_ALREADY_HELD` 또는 `RESERVATION_IN_PROGRESS`); DB에 해당 seatId-X를 가진 PENDING 예매 1건만 존재
- **검증 포인트**: 201 응답 정확히 1개 / 409 응답 9개 / DB PENDING 예매 seatId-X 기준 1건

---

### TC-RSV-003 — 사용자 락 TOCTOU: 동일 유저가 동시 선점 요청 시 즉시 409

- [x] 통과 (2026-05-31, 근거: 동일 userId 동시 2요청 → 1개 201, 1개 409(RESERVATION_IN_PROGRESS))
- **관련 REQ**: REQ-RSV-001, REQ-RSV-005
- **분류**: 동시성
- **우선순위**: P0
- **사전조건**: AVAILABLE 좌석 2개(seatId-P, seatId-Q), Queue Token qr_toctou (userId-1, scheduleId) Redis SET
- **실행 단계**:
  1. Queue Token 삽입
     ```bash
     redis-cli SET queue:token:qr_toctou '{"userId":"<userId-1>","scheduleId":"<scheduleId>"}' EX 600
     ```
  2. 동일 userId로 두 요청을 동시 발사 (각각 다른 좌석 지정)
     ```bash
     curl -s -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-1>" -H "X-Queue-Token: qr_toctou" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId>","seatIds":["<seatId-P>"]}' &
     curl -s -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-1>" -H "X-Queue-Token: qr_toctou" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId>","seatIds":["<seatId-Q>"]}' &
     wait
     ```
  3. 결과 확인: 1개 201, 1개 409(`RESERVATION_IN_PROGRESS`) 기대
- **기대 결과**: 1개 HTTP 201, 1개 HTTP 409(code=`RESERVATION_IN_PROGRESS`); `user:hold:lock:<userId-1>:<scheduleId>` Redisson 락이 waitTime=0으로 즉시 실패 처리
- **검증 포인트**: 409 응답 body의 `code` 필드가 `RESERVATION_IN_PROGRESS` / DB PENDING 예매 1건만 생성

---

### TC-RSV-004 — 만료된 Queue Token으로 선점 시 401 거부

- [x] 통과 (2026-05-31, 근거: Redis에 토큰 미존재 확인 후 요청 → HTTP 401, code=QUEUE_TOKEN_EXPIRED)
- **관련 REQ**: REQ-RSV-008
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: Redis에 해당 token key 없음(만료 or 미존재)
- **실행 단계**:
  1. Redis에서 토큰 존재하지 않음을 확인
     ```bash
     redis-cli EXISTS queue:token:expired_token
     # 결과: 0
     ```
  2. 만료된 토큰으로 선점 요청
     ```bash
     curl -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-1>" \
       -H "X-Queue-Token: expired_token" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId>","seatIds":["<seatId-A>"]}'
     ```
- **기대 결과**: HTTP 401, 응답 `code=QUEUE_TOKEN_EXPIRED`
- **검증 포인트**: HTTP 401 / 응답 JSON `code` = `QUEUE_TOKEN_EXPIRED` / DB에 예매 row 미생성

---

### TC-RSV-005 — 다른 scheduleId의 Queue Token으로 선점 시 401 거부

- [x] 통과 (2026-05-31, 근거: scheduleId-B 토큰으로 scheduleId-A 선점 시도 → HTTP 401, code=QUEUE_TOKEN_INVALID)
- **관련 REQ**: REQ-RSV-008
- **분류**: 보안
- **우선순위**: P1
- **사전조건**: Queue Token이 scheduleId-B로 발급된 상태; 요청 대상은 scheduleId-A
- **실행 단계**:
  1. scheduleId-B 용 토큰 삽입
     ```bash
     redis-cli SET queue:token:qr_wrong_sched '{"userId":"<userId-1>","scheduleId":"<scheduleId-B>"}' EX 600
     ```
  2. scheduleId-A 대상으로 요청
     ```bash
     curl -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-1>" \
       -H "X-Queue-Token: qr_wrong_sched" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId-A>","seatIds":["<seatId-A>"]}'
     ```
- **기대 결과**: HTTP 401, `code=QUEUE_TOKEN_INVALID`
- **검증 포인트**: HTTP 401 / `code=QUEUE_TOKEN_INVALID` / DB 예매 미생성

---

### TC-RSV-006 — 최대 좌석 수 초과 선점 시 400 거부 (단일 요청 5장)

- [x] 통과 (2026-05-31, 근거: 5개 seatIds 전송 → HTTP 400, code=INVALID_INPUT, message="seatIds: size must be between 1 and 4")
- **관련 REQ**: REQ-RSV-005
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: AVAILABLE 좌석 5개, 유효 Queue Token 보유
- **실행 단계**:
  1. Queue Token 삽입
     ```bash
     redis-cli SET queue:token:qr_max5 '{"userId":"<userId-1>","scheduleId":"<scheduleId>"}' EX 600
     ```
  2. 5개 좌석으로 선점 요청
     ```bash
     curl -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-1>" \
       -H "X-Queue-Token: qr_max5" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId>","seatIds":["<s1>","<s2>","<s3>","<s4>","<s5>"]}'
     ```
- **기대 결과**: HTTP 400(`INVALID_INPUT` 또는 `MAX_SEATS_EXCEEDED`); `HoldRequest.seatIds` `@Size(max=4)` Bean Validation 위반
- **검증 포인트**: HTTP 400 / DB 예매 미생성

---

### TC-RSV-007 — 기존 PENDING 예매(3석) 존재 시 추가 2석 선점으로 합산 5석 초과 거부

- [x] 통과 (2026-05-31, 근거: 3석 선점 후 2석 추가 시도 → HTTP 400, code=MAX_SEATS_EXCEEDED, 기존 3석 유지)
- **관련 REQ**: REQ-RSV-005
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: userId-1이 scheduleId에 PENDING 예매(3석) 이미 보유; 추가 선점 요청 2석
- **실행 단계**:
  1. 3석 선점 (TC-RSV-001 방식으로 사전 생성)
  2. 동일 userId로 2석 추가 선점 요청
     ```bash
     redis-cli SET queue:token:qr_extra2 '{"userId":"<userId-1>","scheduleId":"<scheduleId>"}' EX 600
     curl -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-1>" \
       -H "X-Queue-Token: qr_extra2" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId>","seatIds":["<newSeatId-1>","<newSeatId-2>"]}'
     ```
  3. DB에서 기존 예매 좌석 수 확인
     ```sql
     SELECT COUNT(*) FROM reservation_service.reservation_seats rs
     JOIN reservation_service.reservations r ON rs.reservation_id = r.id
     WHERE r.user_id = '<userId-1>' AND r.schedule_id = '<scheduleId>' AND r.status = 'PENDING';
     ```
- **기대 결과**: HTTP 400, `code=MAX_SEATS_EXCEEDED`; 기존 3석 유지, 추가 2석 미생성
- **검증 포인트**: HTTP 400 / `code=MAX_SEATS_EXCEEDED` / DB PENDING 예매 좌석 합산 3석 유지

---

### TC-RSV-008 — 이미 hold_seats SET에 있는 좌석 선점 시 409 거부

- [x] 통과 (2026-05-31, 근거: Redis SADD로 좌석 직접 삽입 후 동일 좌석 선점 시도 → HTTP 409, code=SEAT_ALREADY_HELD)
- **관련 REQ**: REQ-RSV-001
- **분류**: 예외
- **우선순위**: P0
- **사전조건**: `hold_seats:<scheduleId>` SET에 seatId-Z가 이미 등록된 상태(타 사용자가 선점); userId-2는 동일 좌석 선점 시도
- **실행 단계**:
  1. Redis SET에 좌석 직접 삽입 (타 사용자 선점 시뮬레이션)
     ```bash
     redis-cli SADD hold_seats:<scheduleId> <seatId-Z>
     redis-cli EXPIRE hold_seats:<scheduleId> 600
     ```
  2. userId-2로 동일 좌석 선점 시도
     ```bash
     redis-cli SET queue:token:qr_dup '{"userId":"<userId-2>","scheduleId":"<scheduleId>"}' EX 600
     curl -X POST http://localhost:8084/reservations/hold \
       -H "X-User-Id: <userId-2>" \
       -H "X-Queue-Token: qr_dup" \
       -H "Content-Type: application/json" \
       -d '{"scheduleId":"<scheduleId>","seatIds":["<seatId-Z>"]}'
     ```
- **기대 결과**: HTTP 409, `code=SEAT_ALREADY_HELD`; DB에 userId-2 예매 미생성
- **검증 포인트**: HTTP 409 / `code=SEAT_ALREADY_HELD` / DB 예매 미생성 / `sMIsMember` 기반 SET 조회 확인(KEYS 명령 미사용)

---

### TC-RSV-009 — Outbox 패턴: 선점 해제(취소) 시 직접 Kafka 발행 금지, outbox_events만 INSERT

- [ ] 실패 (사유: outbox INSERT·Kafka 발행 정상이나 cancelReservation() 내 detached 엔티티 미반영으로 DB status='PENDING' 유지됨, 이슈: #287)
- **관련 REQ**: REQ-RSV-011, REQ-RSV-012
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: PENDING 예매 1건 존재(reservationId-1), userId-1 소유
- **실행 단계**:
  1. outbox_events 현재 카운트 확인
     ```sql
     SELECT COUNT(*) FROM common.outbox_events WHERE published = false;
     ```
  2. 예매 취소 요청
     ```bash
     curl -X DELETE http://localhost:8084/reservations/<reservationId-1> \
       -H "X-User-Id: <userId-1>"
     ```
  3. outbox_events INSERT 확인
     ```sql
     SELECT id, aggregate_type, event_type, payload, published
     FROM common.outbox_events
     WHERE aggregate_id = '<reservationId-1>'
     ORDER BY created_at DESC LIMIT 1;
     ```
  4. Kafka 토픽에 즉시 메시지가 없음을 확인(Outbox Poller 1초 대기 전)
     ```bash
     kafka-console-consumer.sh --bootstrap-server localhost:9092 \
       --topic reservation.events --from-beginning --timeout-ms 800
     ```
- **기대 결과**: DB `common.outbox_events`에 `event_type='ReservationCancelled'`, `published=false` row 1건 INSERT; 1초 후 Poller가 발행하여 `published=true`로 갱신; Kafka `reservation.events` 토픽에 메시지 도달
- **검증 포인트**: `outbox_events.event_type='ReservationCancelled'` / `aggregate_id=reservationId-1` / 초기 `published=false` / ~1초 후 `published=true` / Kafka 메시지 수신 확인

---

### TC-RSV-010 — Outbox 트랜잭션 원자성: 비즈니스 롤백 시 outbox_events도 미삽입

- [x] 통과 (2026-05-31, 근거: CANCELLED 예매 재취소 시도 → HTTP 409, code=RESERVATION_ALREADY_CANCELLED, outbox 신규 row 0건)
- **관련 REQ**: REQ-RSV-012
- **분류**: 예외
- **우선순위**: P0
- **사전조건**: DB 제약 위반을 유발할 수 있는 상황 준비(예: 이미 CANCELLED 상태인 예매 취소 재시도)
- **실행 단계**:
  1. CANCELLED 상태 예매 생성(또는 TC-RSV-009 결과 활용)
  2. 동일 예매 재취소 시도
     ```bash
     curl -X DELETE http://localhost:8084/reservations/<cancelledReservationId> \
       -H "X-User-Id: <userId-1>"
     ```
  3. outbox_events 미삽입 확인
     ```sql
     SELECT COUNT(*) FROM common.outbox_events
     WHERE aggregate_id = '<cancelledReservationId>'
       AND created_at > now() - INTERVAL '5 seconds';
     ```
- **기대 결과**: HTTP 409, `code=RESERVATION_ALREADY_CANCELLED`; outbox_events에 신규 row 미삽입
- **검증 포인트**: HTTP 409 / DB outbox_events 신규 row 0건 / `PROPAGATION_MANDATORY` 트랜잭션 범위 내 원자 처리 확인

---

### TC-RSV-011 — hold 만료 배치: holdExpiresAt 경과 PENDING 예매 자동 CANCELLED 및 Outbox 발행

- [x] 통과 (2026-05-31, 근거: 과거 hold_expires_at(UTC 기준)으로 PENDING 예매 INSERT 후 배치 실행(매분 0초) → status=CANCELLED, outbox reason=HOLD_EXPIRED, Redis SISMEMBER=0)
- **관련 REQ**: REQ-RSV-007, REQ-RSV-011
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: hold_expires_at이 과거로 설정된 PENDING 예매 직접 DB INSERT(배치 발동 조건)
- **실행 단계**:
  1. 만료된 PENDING 예매 직접 삽입
     ```sql
     INSERT INTO reservation_service.reservations
       (user_id, schedule_id, event_id, status, total_amount, hold_expires_at)
     VALUES ('<userId-1>','<scheduleId>','<eventId>','PENDING',150000,
             now() - INTERVAL '6 minutes')
     RETURNING id;
     ```
  2. reservation_seats 삽입
     ```sql
     INSERT INTO reservation_service.reservation_seats
       (reservation_id, seat_id, seat_number, grade, price)
     VALUES ('<newReservationId>','<seatId-A>','A-1','VIP',150000);
     ```
  3. 배치 스케줄러가 매분 0초에 실행하므로 최대 60초 대기 후 상태 확인
     ```sql
     SELECT status FROM reservation_service.reservations WHERE id = '<newReservationId>';
     ```
  4. outbox_events 확인
     ```sql
     SELECT event_type, payload->>'reason' AS reason FROM common.outbox_events
     WHERE aggregate_id = '<newReservationId>';
     ```
  5. Redis hold_seats SET에서 seatId-A 제거 확인
     ```bash
     redis-cli SISMEMBER hold_seats:<scheduleId> <seatId-A>
     # 기대: 0
     ```
- **기대 결과**: 배치 실행 후 `status='CANCELLED'`; `outbox_events`에 `event_type='ReservationCancelled'`, `payload.reason='HOLD_EXPIRED'`; Redis SET에서 해당 seatId 제거
- **검증 포인트**: DB `status='CANCELLED'` / outbox reason=`HOLD_EXPIRED` / Redis SISMEMBER=0

---

### TC-RSV-012 — 경계값: holdExpiresAt 직전(만료 1초 전) 배치 스킵, 직후 처리

- [x] 통과 (2026-05-31, 근거: hold_expires_at=now_UTC+65초 삽입 → 첫 번째 배치(~60초 후) PENDING 유지, 두 번째 배치(~120초 후) CANCELLED 전환)
- **관련 REQ**: REQ-RSV-007
- **분류**: 경계값
- **우선순위**: P1
- **사전조건**: 로컬 환경에서 hold_expires_at을 정밀하게 제어 가능
- **실행 단계**:
  1. 만료 시각을 now+65초로 설정한 PENDING 예매 삽입(다음 배치 실행 이후에도 아직 만료 안됨)
     ```sql
     INSERT INTO reservation_service.reservations
       (user_id, schedule_id, event_id, status, total_amount, hold_expires_at)
     VALUES ('<userId-2>','<scheduleId>','<eventId>','PENDING',100000,
             now() + INTERVAL '65 seconds') RETURNING id;
     ```
  2. 다음 배치(~60초 후) 실행 후 상태 확인 → PENDING 유지 기대
     ```sql
     SELECT status FROM reservation_service.reservations WHERE id = '<newId>';
     -- 기대: PENDING
     ```
  3. 65초 이후 두 번째 배치 실행 후 상태 확인 → CANCELLED 기대
     ```sql
     SELECT status FROM reservation_service.reservations WHERE id = '<newId>';
     -- 기대: CANCELLED
     ```
- **기대 결과**: 첫 배치(만료 전) → `status='PENDING'` 유지; 두 번째 배치(만료 후) → `status='CANCELLED'`
- **검증 포인트**: 두 배치 사이 상태 전환 확인 / outbox_events 두 번째 배치 이후 삽입

---

### TC-RSV-013 — Kafka Consumer 멱등성: 동일 PaymentSuccess 이벤트 재전송 시 processed_events 중복 차단

- [x] 통과 (2026-05-31, 근거: 동일 eventId PaymentSuccess 2회 발행 → status=CONFIRMED 1회 전이, processed_events row 1건, 두 번째 DataIntegrityViolationException 처리 후 ack)
- **관련 REQ**: REQ-RSV-004, REQ-RSV-012
- **분류**: 멱등성
- **우선순위**: P0
- **사전조건**: PENDING 예매 1건(reservationId-X), 동일 eventId의 PaymentSuccess 이벤트 메시지 2개
- **실행 단계**:
  1. PaymentSuccess JSON 준비(eventId 고정, 반드시 유효한 UUID 형식)
     ```json
     {
       "eventId": "f1000000-1111-2222-3333-000000000001",
       "eventType": "PaymentSuccess",
       "aggregateId": "<paymentId>",
       "aggregateType": "Payment",
       "version": "v1",
       "timestamp": "2026-05-29T10:00:00",
       "metadata": {"correlationId": "<uuid>", "causationId": null, "userId": "<userId-1>"},
       "paymentKey": "pay-key-001",
       "reservationId": "<reservationId-X>",
       "amount": 150000,
       "paidAt": "2026-05-29T10:00:00",
       "scheduleId": "<scheduleId>",
       "seatIds": ["<seatId-A>"],
       "portoneTransactionId": "portone-tx-001"
     }
     ```
  2. 동일 메시지 payment.events 토픽에 2회 발행
     ```bash
     echo '<json>' | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment.events
     echo '<json>' | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment.events
     ```
  3. 처리 완료 후 예매 상태 확인
     ```sql
     SELECT status, ticket_number FROM reservation_service.reservations WHERE id = '<reservationId-X>';
     ```
  4. processed_events 확인
     ```sql
     SELECT COUNT(*) FROM common.processed_events
     WHERE event_id = 'f1000000-1111-2222-3333-000000000001' AND consumer_service = 'reservation-service';
     ```
- **기대 결과**: 예매 `status='CONFIRMED'`로 1회만 전이; `processed_events`에 (fixed-event-uuid-001, reservation-service) row 1건만 존재; 두 번째 메시지는 `DataIntegrityViolationException` 처리 후 ack 및 스킵
- **검증 포인트**: DB `status='CONFIRMED'` / `processed_events` row 1건 / 로그에 "Skipping duplicate event" 포함

---

### TC-RSV-014 — PaymentFailed Consumer: SAGA 보상 체인 — 예매 CANCELLED + ReservationCancelled Outbox 발행

- [x] 통과 (2026-05-31, 근거: PaymentFailed 이벤트 발행 → status=CANCELLED, outbox reason=PAYMENT_FAILED, causationId 일치, hold_seats SISMEMBER=0)
- **관련 REQ**: REQ-RSV-004, REQ-RSV-011
- **분류**: 보상트랜잭션
- **우선순위**: P0
- **사전조건**: PENDING 예매(reservationId-Y) 존재
- **실행 단계**:
  1. PaymentFailed 이벤트 발행
     ```bash
     echo '{"eventId":"fail-evt-001","eventType":"PaymentFailed","aggregateId":"<paymentId>","aggregateType":"Payment","version":"v1","timestamp":"2026-05-29T10:05:00","metadata":{"correlationId":"<uuid>","causationId":null,"userId":"<userId-1>"},"reservationId":"<reservationId-Y>","reason":"INSUFFICIENT_BALANCE"}' \
     | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment.events
     ```
  2. 예매 상태 확인
     ```sql
     SELECT status FROM reservation_service.reservations WHERE id = '<reservationId-Y>';
     ```
  3. outbox_events에 ReservationCancelled 확인
     ```sql
     SELECT event_type, payload->>'reason' AS reason, payload->>'causationId' AS causation_id
     FROM common.outbox_events WHERE aggregate_id = '<reservationId-Y>';
     ```
- **기대 결과**: `status='CANCELLED'`; `outbox_events`에 `event_type='ReservationCancelled'`, `reason='PAYMENT_FAILED'`, `metadata.causationId='fail-evt-001'`(이벤트 체인 추적 가능)
- **검증 포인트**: DB `status='CANCELLED'` / outbox reason=`PAYMENT_FAILED` / causationId=`fail-evt-001` / hold_seats SET에서 해당 좌석 SREM 확인

---

### TC-RSV-015 — PaymentFailed Consumer 멱등성: 이미 CANCELLED 예매에 재전송 시 no-op

- [x] 통과 (2026-05-31, 근거: CANCELLED 예매에 새 eventId PaymentFailed 재전송 → 상태 불변, outbox 신규 row 0건, 로그 "Reservation already cancelled (idempotent skip)" 확인)
- **관련 REQ**: REQ-RSV-004
- **분류**: 멱등성
- **우선순위**: P1
- **사전조건**: reservationId-Y가 이미 CANCELLED 상태(TC-RSV-014 이후 활용 가능)
- **실행 단계**:
  1. 동일 PaymentFailed 이벤트 재전송(새 eventId 사용)
     ```bash
     echo '{"eventId":"fail-evt-002","eventType":"PaymentFailed","aggregateId":"<paymentId>","aggregateType":"Payment","version":"v1","timestamp":"2026-05-29T10:05:01","metadata":{"correlationId":"<uuid>","causationId":null,"userId":"<userId-1>"},"reservationId":"<reservationId-Y>","reason":"INSUFFICIENT_BALANCE"}' \
     | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment.events
     ```
  2. outbox_events 신규 row 미삽입 확인
     ```sql
     SELECT COUNT(*) FROM common.outbox_events
     WHERE aggregate_id = '<reservationId-Y>' AND created_at > now() - INTERVAL '10 seconds';
     ```
- **기대 결과**: 예매 상태 CANCELLED 유지; 신규 outbox_events 미삽입; 로그에 "Reservation already cancelled (idempotent skip)" 포함
- **검증 포인트**: DB 상태 불변 / outbox 신규 row 0건 / 서비스 로그 확인

---

### TC-RSV-016 — DLQ 전략: 잘못된 JSON 수신 시 재시도 없이 즉시 dlq.payment 이동

- [x] 통과 (2026-05-31, 근거: 파싱 불가 JSON 발행 → 서비스 로그 "Malformed JSON in payment.events, sending to DLQ" 즉시 출력, dlq.payment 토픽에 메시지 도달, 재시도 로그 없음)
- **관련 REQ**: REQ-RSV-004
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: Kafka `payment.events`, `dlq.payment` 토픽 존재
- **실행 단계**:
  1. 파싱 불가능한 JSON 발행
     ```bash
     echo '{"eventType":"PaymentSuccess","brokenJson":' \
     | kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment.events
     ```
  2. DLQ 수신 확인
     ```bash
     kafka-console-consumer.sh --bootstrap-server localhost:9092 \
       --topic dlq.payment --from-beginning --timeout-ms 5000
     ```
  3. 로그에서 즉시 DLQ 이동 확인(재시도 0회)
     ```bash
     grep "Malformed.*JSON" /var/log/reservation-service.log | tail -5
     ```
- **기대 결과**: `dlq.payment` 토픽에 해당 메시지 도달; 재시도 백오프 없이 즉시 이동(`JsonProcessingException` = non-retryable); 서비스 로그에 "Malformed JSON in payment.events" 포함
- **검증 포인트**: dlq.payment에 메시지 존재 / 재시도 로그 없음 / 서비스 정상 운영 지속

---

### TC-RSV-017 — DLQ 전략: TimeoutException 발생 시 지수 백오프 3회 후 dlq.payment 이동

- [x] 통과 (2026-05-31, 근거: 서비스 기동 로그 "Kafka error handler configured: backoff=exponential(1s/2x/10s), maxRetries=3" 및 실제 Retryable error 로그 패턴 확인. KafkaErrorHandlerConfig: initialInterval=1000, multiplier=2.0, maxElapsedTime=15000)
- **관련 REQ**: REQ-RSV-004
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: integrationTest 환경에서 DB 타임아웃 유발 가능(또는 단위 테스트로 Mock 활용)
- **실행 단계**:
  1. `./gradlew :reservation-service:integrationTest --tests "*DlqRetryIntegrationTest*"` 실행 (또는 테스트 클래스 직접 지정)
  2. 테스트 내에서 DB QueryTimeoutException 주입, 3회 재시도 후 DLQ 전송 검증
  3. 서비스 로그에서 재시도 패턴 확인
     ```bash
     grep -E "Retryable error|warn.*retrying" /var/log/reservation-service.log | tail -10
     ```
- **기대 결과**: 1초 → 2초 → 4초 백오프 후 3회 실패 시 `dlq.payment` 이동; 재시도 간격 로그 확인
- **검증 포인트**: dlq.payment 메시지 존재 / 재시도 3회 로그 패턴 / 처리 완료까지 ~7초 소요

---

### TC-RSV-018 — 내부 API 보안: X-Service-Api-Key 없이 /internal/reservations/{id} 호출 시 401

- [x] 통과 (2026-05-31, 근거: API Key 미포함 → HTTP 401, code=INTERNAL_API_UNAUTHORIZED; API Key 포함 → HTTP 200, 예매 상세 반환)
- **관련 REQ**: 해당 없음 (아키텍처 원칙)
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: reservation-service 기동, reservationId-Z 존재
- **실행 단계**:
  1. Api Key 없이 내부 API 호출
     ```bash
     curl -v http://localhost:8084/internal/reservations/<reservationId-Z>
     ```
  2. 올바른 Api Key로 호출
     ```bash
     curl -v http://localhost:8084/internal/reservations/<reservationId-Z> \
       -H "X-Service-Api-Key: local-dev-internal-api-key"
     ```
- **기대 결과**: Api Key 없는 요청 → HTTP 401, `code=INTERNAL_API_UNAUTHORIZED`; Api Key 포함 요청 → HTTP 200 및 예매 상세 반환
- **검증 포인트**: Api Key 미포함 시 401 / Api Key 포함 시 200 / `InternalApiAuthInterceptor` 동작 확인

---

### TC-RSV-019 — 스키마 격리: reservation_svc_user 계정으로 event_service 스키마 직접 쿼리 시 권한 거부

- [x] 통과 (2026-05-31, 근거: event_service 스키마 접근 → "ERROR: permission denied for schema event_service"; reservation_service 스키마 접근 → 정상 결과)
- **관련 REQ**: 해당 없음 (아키텍처 원칙)
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: PostgreSQL에 reservation_svc_user 계정 존재(application.yml 기준 DB 사용자)
- **실행 단계**:
  1. reservation_svc_user 계정으로 event_service 스키마 접근 시도
     ```bash
     psql -h localhost -p 5432 -U reservation_svc_user -d ticket_queue \
       -c "SELECT * FROM event_service.seats LIMIT 1;"
     ```
  2. 동일 계정으로 reservation_service 스키마 접근 (정상 확인)
     ```bash
     psql -h localhost -p 5432 -U reservation_svc_user -d ticket_queue \
       -c "SELECT id, status FROM reservation_service.reservations LIMIT 1;"
     ```
- **기대 결과**: event_service 스키마 쿼리 → `ERROR: permission denied for schema event_service`; reservation_service 쿼리 → 정상 결과 반환
- **검증 포인트**: psql 에러 메시지 `permission denied` / reservation_service 쿼리 정상

---

### TC-RSV-020 — 선점 해제 API: DELETE /hold/{reservationId} 후 hold_seats SET에서 좌석 제거 확인

- [ ] 실패 (사유: HTTP 200·응답 status=CANCELLED·Redis SISMEMBER=0 정상이나 cancelReservation() detached 엔티티 버그로 DB status='PENDING' 미갱신, 이슈: #287)
- **관련 REQ**: REQ-RSV-006
- **분류**: 정상
- **우선순위**: P1
- **사전조건**: PENDING 예매(reservationId-D), 해당 좌석들이 `hold_seats:<scheduleId>` SET에 등록된 상태
- **실행 단계**:
  1. 선점 전 Redis SET 상태 확인
     ```bash
     redis-cli SMEMBERS hold_seats:<scheduleId>
     ```
  2. 선점 해제 요청
     ```bash
     curl -X DELETE http://localhost:8084/reservations/<reservationId-D> \
       -H "X-User-Id: <userId-1>"
     ```
  3. Redis SET에서 좌석 제거 확인
     ```bash
     redis-cli SISMEMBER hold_seats:<scheduleId> <seatId-D>
     # 기대: 0
     ```
  4. DB 상태 확인
     ```sql
     SELECT status FROM reservation_service.reservations WHERE id = '<reservationId-D>';
     ```
- **기대 결과**: HTTP 200, `status='CANCELLED'`, `refundAmount=0`; Redis `hold_seats:<scheduleId>` SET에서 해당 좌석 제거(afterCommit 콜백)
- **검증 포인트**: HTTP 200 / DB `status='CANCELLED'` / Redis SISMEMBER=0 / PENDING 취소 시 refundAmount=0

---

### TC-RSV-021 — 공연 당일 취소 불가: cancelReservation 호출 시 422 거부

- [x] 통과 (2026-05-31, 근거: 오늘(2026-05-31 UTC) event_start_at 회차에 연결된 예매 취소 시도 → HTTP 422, code=CANCELLATION_NOT_ALLOWED, DB 상태 불변, outbox 0건)
- **관련 REQ**: REQ-RSV-006
- **분류**: 엣지
- **우선순위**: P1
- **사전조건**: PENDING 또는 CONFIRMED 예매가 오늘 날짜(UTC) 공연 회차에 연결된 상태; Event Service에서 해당 scheduleId의 eventStartAt이 오늘 날짜
- **실행 단계**:
  1. 오늘 날짜 회차에 대한 예매 존재 확인
  2. 취소 요청
     ```bash
     curl -X DELETE http://localhost:8084/reservations/<reservationId-today> \
       -H "X-User-Id: <userId-1>"
     ```
- **기대 결과**: HTTP 422, `code=CANCELLATION_NOT_ALLOWED`; 예매 상태 변경 없음, outbox_events 미삽입
- **검증 포인트**: HTTP 422 / DB 상태 불변 / outbox 신규 row 미생성

---

### TC-RSV-022 — hold_seats SET TTL 설계: KEYS 명령 미사용 및 SET 기반 O(1) 조회 확인

- [x] 통과 (2026-05-31, 근거: Redis MONITOR 로그에서 KEYS 명령 0건, SMISMEMBER·SADD·SMEMBERS 명령 확인, hold_seats TTL=598(600 이하 양수), Lua 스크립트 SADD+EXPIRE 원자 처리 흔적 확인)
- **관련 REQ**: REQ-RSV-001, REQ-RSV-003
- **분류**: 엣지
- **우선순위**: P1
- **사전조건**: reservation-service 운영 중, Redis slowlog 활성화
- **실행 단계**:
  1. Redis slowlog 및 monitor로 KEYS 명령 발생 여부 확인
     ```bash
     redis-cli CONFIG SET slowlog-log-slower-than 0
     redis-cli MONITOR > /tmp/redis_monitor.log &
     MONITOR_PID=$!
     ```
  2. 좌석 선점 요청 (TC-RSV-001 방식)
  3. 좌석 상태 조회 요청
     ```bash
     curl http://localhost:8084/reservations/seats/<scheduleId> \
       -H "X-User-Id: <userId-1>" -H "X-Queue-Token: qr_test01"
     ```
  4. KEYS 명령 미발생 확인
     ```bash
     kill $MONITOR_PID
     grep -i "\"keys\"" /tmp/redis_monitor.log
     # 기대: 0건
     ```
  5. SMEMBERS, SADD, SREM, SISMEMBER 명령 발생 확인
     ```bash
     grep -E '"SMEMBERS"|"SADD"|"SREM"|"SMISMEMBER"' /tmp/redis_monitor.log | head -10
     ```
  6. hold_seats SET TTL 확인
     ```bash
     redis-cli TTL hold_seats:<scheduleId>
     # 기대: 600 이하 양수
     ```
- **기대 결과**: `KEYS` 명령 0건; `hold_seats:<scheduleId>` 조회는 `SMEMBERS` 또는 `SMISMEMBER`(sMIsMember) 사용; SET TTL = 600초; Lua 스크립트(`SADD + EXPIRE` 원자 처리) 흔적 확인
- **검증 포인트**: monitor 로그에 KEYS 0건 / SET 계열 명령 존재 / TTL > 0
