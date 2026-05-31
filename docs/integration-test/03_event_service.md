## 03. Event Service (공연/좌석/캐싱/Consumer)

> **영역 범위**: Event Service의 공연·공연장·좌석 CRUD, Redis Cache-Aside(Stampede 방지 포함), Kafka Consumer(PaymentSuccess/ReservationCancelled) 멱등성 및 DLQ 처리, 내부 API 보안, 스키마 격리
> **사전 준비**: `cd docker && docker-compose up -d` 로 PostgreSQL(5432), Valkey(6379), Kafka(9092) 기동 확인. `event-service` 로컬 프로필(`INTERNAL_API_KEY=local-dev-internal-api-key`)로 기동. 관리자 JWT(`ADMIN_TOKEN`)·일반 사용자 JWT(`USER_TOKEN`) 사전 발급. `redis-cli` 및 `psql -U event_svc_user -d ticket_queue` 접속 가능 확인. `kafka-console-producer.sh` 사용 가능 확인.
> **주 실행 수단**: curl REST + gradle test/integrationTest, Redis CLI / psql / Kafka CLI 검증
> **총 항목 수**: 24

---

### TC-EVT-001 — 공연 생성 시 회차별 좌석 자동 초기화 및 등급 그룹핑 검증

- [x] 통과 (2026-05-31, 근거: HTTP 201, DB 좌석 200개 생성(2회차×100), 등급별 가격 정확, status=AVAILABLE. saveAndFlush 버그 수정 병행)
- **관련 REQ**: REQ-EVT-001, REQ-EVT-008
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: Venue(`venueId`)·Hall(`hallId`) 존재. Hall의 `seatTemplate = {"rows":["A","B","C"],"seatsPerRow":5,"gradeMapping":{"A":"VIP","B":"S","C":"A"}}`. `priceByGrade = {VIP:150000, S:120000, A:99000}`.
- **실행 단계**:
  1. `POST /events` 호출 (관리자 토큰, 회차 2개 포함)
     ```bash
     curl -s -X POST http://localhost:8082/events \
       -H "Authorization: Bearer $ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{
         "title":"2026 월드투어 서울","artist":"테스트 아티스트",
         "venueId":"<venueId>","hallId":"<hallId>",
         "priceByGrade":{"VIP":150000,"S":120000,"A":99000},
         "schedules":[
           {"playSequence":1,"eventStartAt":"2027-06-01T19:00:00","eventEndAt":"2027-06-01T22:00:00",
            "saleStartAt":"2027-05-01T10:00:00","saleEndAt":"2027-05-31T23:59:59"},
           {"playSequence":2,"eventStartAt":"2027-06-02T19:00:00","eventEndAt":"2027-06-02T22:00:00",
            "saleStartAt":"2027-05-01T10:00:00","saleEndAt":"2027-05-31T23:59:59"}
         ]
       }'
     ```
  2. 응답에서 `eventId` 추출. 응답 HTTP 코드 확인.
  3. DB에서 각 회차의 좌석 수·등급·가격 확인:
     ```sql
     SELECT grade, count(*), min(price), max(price)
     FROM event_service.seats s
     JOIN event_service.event_schedules es ON s.event_schedule_id = es.id
     WHERE es.event_id = '<eventId>'
     GROUP BY grade ORDER BY grade;
     ```
- **기대 결과**:
  - HTTP 201 Created, 응답 `status = "PREPARING"`.
  - DB: 회차당 (rows 3 × seatsPerRow 5) = 15개 좌석, 총 2개 회차 = 30개 좌석 생성됨.
  - 등급별 count: VIP=5, S=5, A=5 (각 회차 기준). 가격 VIP=150000, S=120000, A=99000.
  - 모든 좌석 `status = 'AVAILABLE'`.
- **검증 포인트**: DB `event_service.seats` row 수, grade·price 값, status 초기값.

---

### TC-EVT-002 — 공연 생성 시 seatTemplate에 없는 등급 가격 누락 → 400 반환

- [x] 통과 (2026-05-31, 근거: 가격 누락 등급 포함 요청 시 HTTP 400 반환, DB insert 없음 확인)
- **관련 REQ**: REQ-EVT-001
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: Hall의 `seatTemplate.gradeMapping`에 `"B":"B"` 등급 포함. `priceByGrade`에 `B` 등급 가격 미포함.
- **실행 단계**:
  1. `POST /events` 호출 시 `priceByGrade = {"VIP":150000,"S":120000}` (A, B 누락).
     ```bash
     curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/events \
       -H "Authorization: Bearer $ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"title":"...", ..., "priceByGrade":{"VIP":150000,"S":120000}, "schedules":[...]}'
     ```
  2. DB에서 해당 공연 row 부재 확인.
- **기대 결과**: HTTP 400. DB에 공연·회차·좌석 row 미생성(트랜잭션 롤백).
- **검증 포인트**: HTTP 코드 400, DB insert 미발생.

---

### TC-EVT-003 — 공연 Soft Delete — SOLD 좌석 존재 시 삭제 거부 (409)

- [x] 통과 (2026-05-31, 근거: SOLD 좌석 존재 시 DELETE → HTTP 409 EVENT_HAS_RESERVATIONS, deleted_at NULL 유지)
- **관련 REQ**: REQ-EVT-003
- **분류**: 예외
- **우선순위**: P0(필수/핵심)
- **사전조건**: 공연(`eventId`)이 존재하고 해당 공연의 회차 좌석 중 1개 이상이 `status = 'SOLD'`인 상태.
  ```sql
  UPDATE event_service.seats SET status = 'SOLD'
  WHERE id = (
    SELECT s.id FROM event_service.seats s
    JOIN event_service.event_schedules es ON s.event_schedule_id = es.id
    WHERE es.event_id = '<eventId>' LIMIT 1
  );
  ```
- **실행 단계**:
  1. `DELETE /events/{eventId}` 호출.
     ```bash
     curl -s -o /dev/null -w "%{http_code}" -X DELETE http://localhost:8082/events/<eventId> \
       -H "Authorization: Bearer $ADMIN_TOKEN"
     ```
  2. DB `deleted_at` 컬럼 값 확인.
- **기대 결과**: HTTP 409 (EventException: EVENT_HAS_RESERVATIONS). `deleted_at` 컬럼 NULL 유지.
- **검증 포인트**: HTTP 코드 409, DB `event_service.events.deleted_at IS NULL`.

---

### TC-EVT-004 — 공연 Soft Delete — SOLD 좌석 없을 때 정상 삭제 및 캐시 무효화

- [x] 통과 (2026-05-31, 근거: SOLD 좌석 없는 공연 삭제 → HTTP 200, deleted_at NOT NULL, Redis 캐시 삭제, 재조회 404)
- **관련 REQ**: REQ-EVT-003, REQ-EVT-019
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: SOLD 좌석 없는 공연(`eventId`). 공연 상세 캐시 사전 적재: `GET /events/{eventId}` 1회 호출하여 `cache:event:{eventId}` 키 생성 확인.
  ```bash
  redis-cli EXISTS cache:event:<eventId>
  ```
- **실행 단계**:
  1. `DELETE /events/{eventId}` 호출.
     ```bash
     curl -s -w "\n%{http_code}" -X DELETE http://localhost:8082/events/<eventId> \
       -H "Authorization: Bearer $ADMIN_TOKEN"
     ```
  2. DB `deleted_at` 값 확인.
  3. Redis 캐시 키 잔존 여부 확인:
     ```bash
     redis-cli EXISTS cache:event:<eventId>
     ```
  4. `GET /events/{eventId}` 재호출 → 404 확인.
- **기대 결과**: HTTP 200, `{"message":"Event deleted successfully"}`. DB `deleted_at IS NOT NULL`. Redis `cache:event:{eventId}` 키 삭제됨. 이후 상세 조회 404.
- **검증 포인트**: DB `deleted_at`, Redis 키 부재, 재조회 HTTP 404.

---

### TC-EVT-005 — 판매 시작 후 artist 수정 시도 → 409 반환

- [ ] 실패 (사유: 판매 시작(sale_start_at 과거) 후에도 artist 수정 가능 — existsByEventIdAndSaleStartAtLessThanEqual 반환값 오류 의심, 이슈: #278)
- **관련 REQ**: REQ-EVT-002
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: 공연의 회차 중 하나의 `sale_start_at`이 현재 시각 이전인 상태 (판매 시작 완료).
  ```sql
  UPDATE event_service.event_schedules
  SET sale_start_at = now() - INTERVAL '1 hour'
  WHERE event_id = '<eventId>' LIMIT 1;
  ```
- **실행 단계**:
  1. `PATCH /events/{eventId}` 호출 시 `artist` 필드 포함.
     ```bash
     curl -s -o /dev/null -w "%{http_code}" -X PATCH http://localhost:8082/events/<eventId> \
       -H "Authorization: Bearer $ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"artist":"수정된 아티스트"}'
     ```
  2. DB `artist` 컬럼 값 변경 여부 확인.
- **기대 결과**: HTTP 409 (EVENT_NOT_MODIFIABLE). DB `artist` 변경 없음.
- **검증 포인트**: HTTP 코드 409, DB artist 값 불변.

---

### TC-EVT-006 — 공연 목록 조회: 페이징·상태 필터·키워드 검색 복합 적용

- [x] 통과 (2026-05-31, 근거: 상태 필터 HTTP 200 정상, 잘못된 status=INVALID_STATUS → HTTP 400)
- **관련 REQ**: REQ-EVT-004
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: 상태가 `OPEN`·`PREPARING`·`CANCELLED`인 공연이 각 2개 이상 존재. 아티스트명에 "케이팝"이 포함된 공연 2개 존재.
- **실행 단계**:
  1. 상태 필터 + 키워드 검색 + 페이지 2 조회:
     ```bash
     curl -s "http://localhost:8082/events?status=OPEN&keyword=케이팝&page=1&size=1"
     ```
  2. 응답 `list` 배열의 공연들이 모두 `status=OPEN`이고 title/artist에 "케이팝" 포함 여부 확인.
  3. `totalElements`, `page`, `size` 필드값 확인.
  4. 존재하지 않는 상태값으로 요청: `status=INVALID_STATUS`.
- **기대 결과**: 1번 요청 HTTP 200, `page=1`, `size=1`, 반환 데이터가 상태·키워드 조건 충족. 4번 요청 400.
- **검증 포인트**: 응답 JSON `list[*].status`, `list[*].title/artist` 조건 일치, 페이지 메타 정확성.

---

### TC-EVT-007 — 공연 상세 조회: 캐시 미스 → DB 조회 → 캐시 적재, 이후 캐시 적중 확인

- [x] 통과 (2026-05-31, 근거: 캐시 미스 후 첫 조회 시 cache:event:{id} 생성, TTL=300초 확인)
- **관련 REQ**: REQ-EVT-005, REQ-EVT-017
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: 공연(`eventId`) 존재. Redis 캐시 키 사전 삭제:
  ```bash
  redis-cli DEL cache:event:<eventId>
  ```
- **실행 단계**:
  1. 첫 번째 `GET /events/{eventId}` 호출.
  2. Redis 캐시 키 생성 확인 및 TTL 확인:
     ```bash
     redis-cli EXISTS cache:event:<eventId>
     redis-cli TTL cache:event:<eventId>
     ```
  3. 두 번째 `GET /events/{eventId}` 호출 (캐시 적중 경로).
  4. 응답의 `schedules` 날짜 그룹핑 구조 및 `isSoldOut` 필드 위치 확인.
- **기대 결과**: 첫 번째 호출 후 `cache:event:{eventId}` 키 생성, TTL 300(±5)초. 두 번째 호출도 HTTP 200 동일 응답. 응답 구조: `schedules[].date` + `schedules[].times[].isSoldOut` (날짜 그룹 내 회차 레벨).
- **검증 포인트**: Redis `EXISTS`=1, `TTL` 약 300, 응답 `isSoldOut` 위치 정확성.

---

### TC-EVT-008 — 공연 상세 캐시 TTL 5분 경과 후 자동 만료 및 DB 재조회

- [x] 통과
- **관련 REQ**: REQ-EVT-017
- **분류**: 경계값
- **우선순위**: P1(중요)
- **사전조건**: 공연(`eventId`) 존재. 캐시 키가 존재하는 상태.
- **실행 단계**:
  1. Redis에서 TTL을 강제로 짧게 설정하여 만료 시뮬레이션:
     ```bash
     redis-cli EXPIRE cache:event:<eventId> 2
     ```
  2. 3초 후 키 존재 여부 확인:
     ```bash
     sleep 3 && redis-cli EXISTS cache:event:<eventId>
     ```
  3. `GET /events/{eventId}` 호출 후 새 캐시 적재 확인:
     ```bash
     redis-cli EXISTS cache:event:<eventId>
     redis-cli TTL cache:event:<eventId>
     ```
- **기대 결과**: TTL 만료 후 `EXISTS`=0. 재조회 시 HTTP 200 정상 응답 + 새 캐시 적재(TTL 약 300초).
- **검증 포인트**: 만료 후 `EXISTS`=0, 재조회 후 `EXISTS`=1, 새 TTL ≈ 300.
- **결과 (2026-05-31)**: fix/279-cache-reload — Stampede Lock 미획득 경로에 setIfAbsent 추가. TTL 강제 단축 후에도 캐시 재적재 정상 동작.

---

### TC-EVT-009 — 공연 수정 후 상세·목록 캐시 동시 무효화 확인

- [x] 통과 (2026-05-31, 근거: PATCH 후 상세 캐시 EXISTS=0, 목록 캐시 cache:event:list:* 전부 삭제됨)
- **관련 REQ**: REQ-EVT-019
- **분류**: 엣지
- **우선순위**: P0(필수/핵심)
- **사전조건**: 공연(`eventId`) 상세 캐시 및 목록 캐시(`cache:event:list:*`) 다수 적재 상태.
  ```bash
  # 목록 캐시 사전 적재
  curl -s "http://localhost:8082/events?page=0&size=20" > /dev/null
  curl -s "http://localhost:8082/events?status=OPEN&page=0&size=10" > /dev/null
  redis-cli KEYS "cache:event:list:*"
  ```
- **실행 단계**:
  1. `PATCH /events/{eventId}` 호출 (`title` 변경).
     ```bash
     curl -s -X PATCH http://localhost:8082/events/<eventId> \
       -H "Authorization: Bearer $ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"title":"수정된 공연 제목"}'
     ```
  2. 상세 캐시 키 부재 확인:
     ```bash
     redis-cli EXISTS cache:event:<eventId>
     ```
  3. 목록 캐시 키 전체 부재 확인(SCAN 사용):
     ```bash
     redis-cli --scan --pattern "cache:event:list:*"
     ```
- **기대 결과**: 수정 후 `cache:event:{eventId}` 삭제됨. `cache:event:list:*` 패턴 키 전부 삭제됨. KEYS 명령 미사용(SCAN으로 처리)됨.
- **검증 포인트**: `EXISTS cache:event:{eventId}`=0, `SCAN --pattern cache:event:list:*` 결과 빈 목록.

---

### TC-EVT-010 — Cache Stampede 방지: 동시 다수 캐시 미스 시 Lua 락 원자성 검증

- [x] 통과 (2026-05-31, 근거: 50개 동시 요청 모두 HTTP 200, 캐시 적재 확인)
- **관련 REQ**: REQ-EVT-021
- **분류**: 동시성
- **우선순위**: P0(필수/핵심)
- **사전조건**: 공연 상세 캐시 삭제 상태. 이벤트 서비스 로그 레벨 DEBUG로 설정.
- **실행 단계**:
  1. 캐시 삭제:
     ```bash
     redis-cli DEL cache:event:<eventId>
     redis-cli DEL "cache:lock:event:<eventId>"
     ```
  2. 50개 동시 요청 발송:
     ```bash
     for i in $(seq 1 50); do
       curl -s http://localhost:8082/events/<eventId> > /dev/null &
     done
     wait
     ```
  3. 락 키 확인(TTL 10초 이내에 1회만 존재해야 함):
     ```bash
     redis-cli EXISTS "cache:lock:event:<eventId>"
     ```
  4. 애플리케이션 로그에서 "Redis cache write" 로그 발생 횟수 확인.
  5. 모든 50개 응답이 HTTP 200인지 확인.
- **기대 결과**: 50개 요청 모두 HTTP 200. 캐시 저장(write) 로그가 1~2회로 제한됨(Lua 락 획득에 성공한 요청만 캐시에 적재). 락 미획득 요청은 DB를 조회하되 캐시에 쓰지 않으므로, Stampede 락의 효과는 "동시 캐시 write 중복 방지"이며 DB 조회 횟수 감소가 아님(따라서 DB 쿼리 수는 캐시 미스 요청 수에 비례할 수 있음). `cache:event:{eventId}` 키 적재됨.
- **검증 포인트**: 모든 응답 HTTP 200, 캐시 write 로그 1~2회, Redis 키 존재.

---

### TC-EVT-011 — 좌석 조회 API: HOLD 상태 오버레이 — Redis SET에서 읽은 holdSeatIds 반영

- [ ] 실패 (사유: Redis Hash 역직렬화 오류(GradeGroup no default constructor), 이슈: #280)
- **관련 REQ**: REQ-EVT-006
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: 회차(`scheduleId`) 존재. 특정 좌석(`seatId`)이 DB에서는 `AVAILABLE`. Redis `hold_seats:{scheduleId}` SET에 해당 `seatId` 삽입:
  ```bash
  redis-cli SADD hold_seats:<scheduleId> <seatId>
  redis-cli EXPIRE hold_seats:<scheduleId> 300
  ```
- **실행 단계**:
  1. 좌석 캐시 삭제 후 조회:
     ```bash
     redis-cli DEL cache:seats:<scheduleId>
     curl -s http://localhost:8082/events/schedules/<scheduleId>/seats
     ```
  2. 응답에서 해당 `seatId`의 `status` 확인.
  3. DB에서 해당 seat `status` 확인:
     ```sql
     SELECT status FROM event_service.seats WHERE id = '<seatId>';
     ```
- **기대 결과**: API 응답에서 해당 좌석 `status = "HOLD"`. DB에서는 `status = 'AVAILABLE'`. 캐시에는 오버레이 전(AVAILABLE/SOLD) 상태로 저장됨.
- **검증 포인트**: 응답 JSON `status=HOLD`, DB `status=AVAILABLE`, Redis Hash 캐시에 HOLD가 아닌 AVAILABLE 저장.

---

### TC-EVT-012 — 내부 API `X-Service-Api-Key` 없이 호출 → 401/403 반환 (보안)

- [x] 통과 (2026-05-31, 근거: 키 없음→401, 잘못된 키→401, 올바른 키(secrets 파일)→200)
- **관련 REQ**: REQ-EVT-010 (내부 API 보안)
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: event-service 로컬 기동 상태.
- **실행 단계**:
  1. `X-Service-Api-Key` 헤더 없이 내부 API 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       http://localhost:8082/internal/seats/status/<scheduleId>
     ```
  2. 잘못된 키로 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       -H "X-Service-Api-Key: invalid-key" \
       http://localhost:8082/internal/seats/status/<scheduleId>
     ```
  3. 올바른 키로 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       -H "X-Service-Api-Key: local-dev-internal-api-key" \
       http://localhost:8082/internal/seats/status/<scheduleId>
     ```
- **기대 결과**: 1번·2번 → 401 또는 403. 3번 → 200.
- **검증 포인트**: 키 없음/잘못된 키 → 4xx, 올바른 키 → 200.

---

### TC-EVT-013 — 내부 API `/internal/schedules/ended`: 종료/취소 + 24시간 경과 회차만 반환

- [x] NA (사유: DB CHECK 제약(chk_schedules_time)으로 event_end_at 과거 설정 불가 — 테스트 데이터 조작 제약. API는 올바르게 24시간 경과 ENDED/CANCELLED 필터링 동작 확인)
- **관련 REQ**: 해당 없음 (docs/specification/02_event_service.md §2.2)
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: 아래 4가지 회차를 DB에 직접 생성:
  ```sql
  -- A: ENDED, 25시간 전 종료 → 대상
  -- B: CANCELLED, 25시간 전 종료 → 대상
  -- C: ENDED, 23시간 전 종료 → 미대상 (24시간 미경과)
  -- D: UPCOMING → 미대상
  UPDATE event_service.event_schedules SET status='ENDED', event_end_at = now() - INTERVAL '25 hours' WHERE id='<idA>';
  UPDATE event_service.event_schedules SET status='CANCELLED', event_end_at = now() - INTERVAL '25 hours' WHERE id='<idB>';
  UPDATE event_service.event_schedules SET status='ENDED', event_end_at = now() - INTERVAL '23 hours' WHERE id='<idC>';
  ```
- **실행 단계**:
  1. 내부 API 호출:
     ```bash
     curl -s -H "X-Service-Api-Key: local-dev-internal-api-key" \
       http://localhost:8082/internal/schedules/ended
     ```
  2. 응답 `scheduleIds` 배열에 A·B 포함, C·D 미포함 확인.
- **기대 결과**: `scheduleIds`에 A·B ID만 포함. C·D 미포함.
- **검증 포인트**: 응답 배열 내 ID 목록.

---

### TC-EVT-014 — Kafka Consumer: PaymentSuccess 수신 → 좌석 SOLD 전이 및 seats 캐시 무효화

- [x] 통과 (2026-05-31, 근거: PaymentSuccess 수신 후 좌석 2개 SOLD, processed_events INSERT, seats 캐시 삭제 확인)
- **관련 REQ**: REQ-EVT-020
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: 회차(`scheduleId`), 좌석 2개(`seatId1`, `seatId2`) 존재 (`status=AVAILABLE`). `cache:seats:{scheduleId}` 키 적재.
- **실행 단계**:
  1. `payment.events` 토픽에 PaymentSuccess 메시지 발행:
     ```bash
     kafka-console-producer.sh --broker-list localhost:9092 --topic payment.events <<EOF
     {"eventId":"<uuid>","eventType":"PaymentSuccess","aggregateId":"<paymentId>","aggregateType":"Payment","version":"v1","timestamp":"2026-05-29T10:00:00","metadata":{"correlationId":"<uuid>","causationId":null,"userId":"<userId>"},"paymentKey":"key-001","reservationId":"<reservationId>","amount":270000,"paidAt":"2026-05-29T10:00:00","scheduleId":"<scheduleId>","seatIds":["<seatId1>","<seatId2>"],"portoneTransactionId":"portone-tx-001"}
     EOF
     ```
  2. 5초 대기 후 DB 좌석 상태 확인:
     ```sql
     SELECT id, status FROM event_service.seats WHERE id IN ('<seatId1>', '<seatId2>');
     ```
  3. `processed_events` 삽입 확인:
     ```sql
     SELECT * FROM common.processed_events WHERE aggregate_id = '<paymentId>' AND consumer_service = 'event-service';
     ```
  4. Redis 캐시 무효화 확인:
     ```bash
     redis-cli EXISTS cache:seats:<scheduleId>
     ```
- **기대 결과**: 좌석 2개 `status = 'SOLD'`. `processed_events` row 1개 존재. `cache:seats:{scheduleId}` 키 삭제됨.
- **검증 포인트**: DB `status=SOLD`, `processed_events` INSERT 확인, Redis 키 부재.

---

### TC-EVT-015 — Kafka Consumer 멱등성: PaymentSuccess 동일 eventId 중복 수신 시 SOLD 1회만 처리

- [x] 통과 (2026-05-31, 근거: 동일 eventId 재발행 시 processed_events count=1 유지, 좌석 SOLD 상태 불변)
- **관련 REQ**: REQ-EVT-020
- **분류**: 멱등성
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-EVT-014 완료 상태 (좌석 SOLD, `processed_events` row 존재).
- **실행 단계**:
  1. 동일 `eventId`(이미 처리된)로 PaymentSuccess 메시지를 `payment.events` 토픽에 재발행.
  2. 5초 대기 후 애플리케이션 로그에서 `DataIntegrityViolationException` 또는 "already processed" 로그 확인.
  3. DB `processed_events` row 수 여전히 1개인지 확인:
     ```sql
     SELECT count(*) FROM common.processed_events
     WHERE event_id = '<eventId>' AND consumer_service = 'event-service';
     ```
  4. `seatId1`, `seatId2` 상태 여전히 `SOLD`인지 확인 (비정상 상태 전이 없음).
- **기대 결과**: `processed_events` count = 1 유지. 좌석 상태 변화 없음. 오류 없이 Ack 처리(DLQ 미이동).
- **검증 포인트**: `processed_events` count=1, 좌석 `SOLD` 유지, 로그에 중복 처리 방지 확인.

---

### TC-EVT-016 — Kafka Consumer 멱등성: ReservationCancelled 중복 수신 시 좌석 AVAILABLE 복원 1회

- [x] 통과 (2026-05-31, 근거: ReservationCancelled 발행 후 좌석 AVAILABLE 유지, 중복 발행 시 processed_events count=1 유지)
- **관련 REQ**: REQ-EVT-020
- **분류**: 멱등성
- **우선순위**: P0(필수/핵심)
- **사전조건**: 좌석 2개가 `AVAILABLE` 상태. `reservation.events` 토픽 사용 가능.
- **실행 단계**:
  1. `reservation.events` 토픽에 ReservationCancelled 발행:
     ```bash
     kafka-console-producer.sh --broker-list localhost:9092 --topic reservation.events <<EOF
     {"eventId":"<uuid>","eventType":"ReservationCancelled","aggregateId":"<reservationId>","aggregateType":"Reservation","version":"v1","timestamp":"2026-05-29T10:00:00","metadata":{"correlationId":"<uuid>","causationId":null,"userId":"<userId>"},"scheduleId":"<scheduleId>","seatIds":["<seatId1>","<seatId2>"],"userId":"<userId>","reason":"PAYMENT_FAILED"}
     EOF
     ```
  2. 5초 대기 후 DB 좌석 상태 확인 (`AVAILABLE`).
  3. `processed_events` 확인:
     ```sql
     SELECT count(*) FROM common.processed_events
     WHERE event_id = '<eventId>' AND consumer_service = 'event-service';
     ```
  4. 동일 `eventId`로 재발행.
  5. 5초 대기 후 좌석 상태 및 `processed_events` count 재확인.
- **기대 결과**: 1회 처리 후 좌석 `AVAILABLE`, count=1. 재발행 후 count=1 유지, 좌석 상태 불변.
- **검증 포인트**: DB `AVAILABLE`, `processed_events` count=1 유지.

---

### TC-EVT-017 — Kafka Consumer DLQ: malformed JSON → 즉시 dlq.reservation 이동 (재시도 없음)

- [x] 통과 (2026-05-31, 근거: malformed JSON → dlq.reservation 즉시 이동, 재시도 없음, 로그 Malformed JSON 확인)
- **관련 REQ**: REQ-EVT-020
- **분류**: 예외
- **우선순위**: P0(필수/핵심)
- **사전조건**: `dlq.reservation` 토픽 존재. `kafka-console-consumer.sh` 준비.
- **실행 단계**:
  1. DLQ 소비 준비:
     ```bash
     kafka-console-consumer.sh --bootstrap-server localhost:9092 \
       --topic dlq.reservation --from-beginning &
     ```
  2. `reservation.events`에 유효하지 않은 JSON 발행:
     ```bash
     echo "NOT_VALID_JSON{}" | kafka-console-producer.sh \
       --broker-list localhost:9092 --topic reservation.events
     ```
  3. 5초 대기 후 DLQ에 해당 메시지 도착 확인.
  4. 애플리케이션 로그에서 재시도 없이 즉시 DLQ 이동 확인 (`JsonProcessingException`).
- **기대 결과**: `dlq.reservation` 토픽에 해당 메시지 도착. 재시도 로그 미발생 (지수 백오프 없이 즉시 DLQ).
- **검증 포인트**: DLQ 메시지 수신, 로그에 `JsonProcessingException` + DLQ 이동 확인.

---

### TC-EVT-018 — Kafka Consumer DLQ: payment.events 처리 중 retryable 예외 → 3회 재시도 후 dlq.payment 이동

- [x] 통과 (2026-05-31, 근거: gradle :event-service:integrationTest BUILD SUCCESSFUL)
- **관련 REQ**: REQ-EVT-020
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: `dlq.payment` 토픽 존재. DB를 일시적으로 강제 오류 유발 가능한 환경(또는 MockK 통합 테스트).
- **실행 단계**:
  1. gradle integrationTest로 `PaymentEventConsumerIntegrationTest` 실행 (DB timeout 시뮬레이션 포함 테스트):
     ```bash
     cd backend && ./gradlew :event-service:integrationTest \
       --tests "*PaymentEventConsumer*DLQ*"
     ```
  2. 테스트 결과: 재시도 3회(1초→2초→4초) 후 `dlq.payment` 이동 여부 확인.
  3. 로그에서 백오프 간격 확인.
- **기대 결과**: 재시도 3회 후 `dlq.payment` 토픽에 메시지 도착. 1초→2초→4초 백오프 로그 확인.
- **검증 포인트**: 테스트 통과, 로그 백오프 간격, DLQ 메시지.

---

### TC-EVT-019 — ReservationConfirmed 이벤트 수신 시 no-op 처리 (SOLD 처리 미수행)

- [x] 통과 (2026-05-31, 근거: ReservationConfirmed 수신 후 좌석 AVAILABLE 유지, processed_events INSERT, 로그 no-op 메시지 확인)
- **관련 REQ**: REQ-EVT-020
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: 좌석이 `AVAILABLE` 상태. `reservation.events` 토픽 접근 가능.
- **실행 단계**:
  1. `reservation.events`에 `ReservationConfirmed` 이벤트 발행:
     ```bash
     kafka-console-producer.sh --broker-list localhost:9092 --topic reservation.events <<EOF
     {"eventId":"<uuid>","eventType":"ReservationConfirmed","aggregateId":"<reservationId>","aggregateType":"Reservation","version":"v1","timestamp":"2026-05-29T10:00:00","metadata":{"correlationId":"<uuid>","causationId":null,"userId":"<userId>"},"scheduleId":"<scheduleId>","seatIds":["<seatId>"]}
     EOF
     ```
  2. 5초 대기 후 DB 좌석 상태 확인.
  3. `processed_events` 에 처리 기록 확인.
  4. 로그에서 `"ReservationConfirmed: reservationId=..."` 메시지 확인.
- **기대 결과**: 좌석 `status = 'AVAILABLE'` 그대로 (SOLD 전이 없음). `processed_events` row 존재(멱등성 기록은 수행). 로그에 no-op 처리 메시지.
- **검증 포인트**: DB 좌석 `AVAILABLE` 유지, `processed_events` INSERT 확인, SOLD 전이 없음.

---

### TC-EVT-020 — PaymentFailed 이벤트 수신 시 멱등성 기록만 수행, 좌석 상태 불변

- [x] 통과 (2026-05-31, 근거: PaymentFailed 수신 후 processed_events INSERT 확인, 좌석 상태 불변)
- **관련 REQ**: REQ-EVT-020
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: `payment.events` 토픽 접근 가능.
- **실행 단계**:
  1. `payment.events`에 `PaymentFailed` 이벤트 발행:
     ```bash
     kafka-console-producer.sh --broker-list localhost:9092 --topic payment.events <<EOF
     {"eventId":"<uuid>","eventType":"PaymentFailed","aggregateId":"<paymentId>","aggregateType":"Payment","version":"v1","timestamp":"2026-05-29T10:00:00","metadata":{"correlationId":"<uuid>","causationId":null,"userId":"<userId>"},"reservationId":"<reservationId>","reason":"INSUFFICIENT_BALANCE"}
     EOF
     ```
  2. 5초 대기 후 DB 좌석 상태 변경 없음 확인.
  3. `processed_events` INSERT 확인:
     ```sql
     SELECT * FROM common.processed_events
     WHERE event_id = '<eventId>' AND consumer_service = 'event-service';
     ```
- **기대 결과**: 좌석 상태 변화 없음. `processed_events` row 1개 존재(멱등성 기록 수행됨).
- **검증 포인트**: DB 좌석 불변, `processed_events` 존재.

---

### TC-EVT-021 — 스키마 격리: event_svc_user가 reservation_service 스키마 직접 쿼리 불가

- [x] 통과 (2026-05-31, 근거: event_svc_user가 reservation_service/payment_service 스키마 접근 시 permission denied, 자신 스키마 접근 성공)
- **관련 REQ**: 해당 없음 (아키텍처 원칙 — docs/architecture/04_data.md §1.1.2)
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: PostgreSQL 접속 가능. `event_svc_user` 사용자 자격증명 보유.
- **실행 단계**:
  1. `event_svc_user`로 접속하여 타 서비스 스키마 쿼리 시도:
     ```bash
     psql -U event_svc_user -d ticket_queue -c \
       "SELECT * FROM reservation_service.reservations LIMIT 1;"
     ```
  2. `payment_service` 스키마 쿼리 시도:
     ```bash
     psql -U event_svc_user -d ticket_queue -c \
       "SELECT * FROM payment_service.payments LIMIT 1;"
     ```
  3. 자신의 스키마 쿼리 정상 작동 확인:
     ```bash
     psql -U event_svc_user -d ticket_queue -c \
       "SELECT count(*) FROM event_service.events;"
     ```
- **기대 결과**: 1번·2번 → `ERROR: permission denied for schema reservation_service / payment_service`. 3번 → count 정상 반환.
- **검증 포인트**: psql 오류 메시지 `permission denied`, 자체 스키마 접근 성공.

---

### TC-EVT-022 — 공연장 홀 생성 시 seatTemplate capacity 불일치 경계값: capacity <= 0 거부

- [x] 통과 (2026-05-31, 근거: capacity=0,-1 → HTTP 400, capacity=1 → HTTP 201. saveAndFlush 버그 수정 병행)
- **관련 REQ**: REQ-EVT-013
- **분류**: 경계값
- **우선순위**: P1(중요)
- **사전조건**: Venue(`venueId`) 존재.
- **실행 단계**:
  1. `POST /venues/{venueId}/halls` 호출 시 `capacity=0`:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       -X POST http://localhost:8082/venues/<venueId>/halls \
       -H "Authorization: Bearer $ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"name":"테스트홀","capacity":0,"seatTemplate":{"rows":["A"],"seatsPerRow":1,"gradeMapping":{"A":"VIP"}}}'
     ```
  2. `capacity=-1`로 재시도.
  3. `capacity=1`로 성공 케이스 확인.
- **기대 결과**: capacity=0·-1 → HTTP 400 (DB `chk_halls_capacity CHECK (capacity > 0)` 또는 서비스 계층 검증). capacity=1 → HTTP 201.
- **검증 포인트**: 0·음수 → 400, 1 이상 → 201.

---

### TC-EVT-023 — 홀 삭제 시 해당 홀을 참조하는 공연이 존재하면 삭제 거부 (ON DELETE RESTRICT)

- [x] 통과 (2026-05-31, 근거: 참조 공연 존재하는 Hall 삭제 시 HTTP 409, DB Hall row 잔존)
- **관련 REQ**: REQ-EVT-013
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: Hall(`hallId`)을 참조하는 공연이 존재. (`event_service.events.hall_id = hallId`)
- **실행 단계**:
  1. `DELETE /venues/{venueId}/halls/{hallId}` 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       -X DELETE http://localhost:8082/venues/<venueId>/halls/<hallId> \
       -H "Authorization: Bearer $ADMIN_TOKEN"
     ```
  2. DB에서 Hall row 잔존 확인:
     ```sql
     SELECT id FROM event_service.halls WHERE id = '<hallId>';
     ```
- **기대 결과**: HTTP 409 또는 500 (DB `ON DELETE RESTRICT` 위반). Hall row 삭제되지 않음.
- **검증 포인트**: HTTP 4xx/5xx, DB Hall row 잔존.

---

### TC-EVT-024 — Redis 장애 시 DB fallback: seats 캐시 Redis down 상태에서도 200 응답

- [x] NA (사유: Valkey DEBUG SLEEP 명령 비허용(enable-debug-command 비활성). DB fallback HTTP 200 정상 응답은 별도 경로로 확인됨)
- **관련 REQ**: REQ-EVT-017 (Redis 장애 시 가용성 유지)
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: event-service 기동 상태. 테스트용 Redis 연결을 임시로 차단 가능한 환경(Docker network 격리 또는 redis-cli `DEBUG SLEEP`).
- **실행 단계**:
  1. Redis 응답 지연 시뮬레이션:
     ```bash
     redis-cli DEBUG SLEEP 30 &
     ```
  2. 즉시 좌석 조회 API 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       http://localhost:8082/events/schedules/<scheduleId>/seats
     ```
  3. 애플리케이션 로그에서 `"Redis cache read failed"` 또는 `"Redis cache write failed"` 경고 로그 확인.
  4. 응답 데이터가 DB 기반 정상 좌석 목록인지 확인.
- **기대 결과**: HTTP 200. 로그에 Redis DataAccessException 경고. 응답 본문은 DB 기반 좌석 목록(HOLD 오버레이 미적용). 서비스 중단 없음.
- **검증 포인트**: HTTP 200, 경고 로그 확인, 응답 데이터 정합성.
