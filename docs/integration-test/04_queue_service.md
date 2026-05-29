## 04. Queue Service (Redis 대기열/토큰)

> **영역 범위**: Redis Sorted Set 기반 가상 대기열 진입·상태조회·이탈·배치 승인·Queue Token 발급/검증 전 흐름 (queue-service, port 8083)
> **사전 준비**: `cd docker && docker-compose up -d` 로 Valkey(6379) 기동, queue-service(`./gradlew :queue-service:bootRun`) 실행, event-service Mock 또는 실행 중(port 8082), 유효한 JWT(USER 권한) 및 ADMIN JWT 사전 발급, 테스트 회차 UUID(`SCHEDULE_ID`) 환경변수 설정
> **주 실행 수단**: curl REST + `./gradlew :queue-service:integrationTest`, Redis CLI(`redis-cli -p 6379`) 로 Sorted Set/String 키 직접 검증
> **총 항목 수**: 20

---

### TC-QUEUE-001 — 정상 대기열 진입: WAITING 상태·rank·estimatedWaitTime 반환

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-001, REQ-QUEUE-005
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: Valkey 기동, 해당 scheduleId에 대한 대기열 비어 있음, EVENT_SERVICE가 `sellable=true` 반환
- **실행 단계**:
  1. `SCHEDULE_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')` — 테스트용 회차 ID 생성
  2. ```bash
     curl -s -X POST http://localhost:8083/queue/enter \
       -H "X-User-Id: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
       -H "X-User-Role: USER" \
       -H "Content-Type: application/json" \
       -d "{\"scheduleId\": \"$SCHEDULE_ID\"}"
     ```
  3. Redis 상태 확인: `redis-cli ZRANK "queue:$SCHEDULE_ID" <userId>` — 0 반환 확인
  4. `redis-cli GET "queue:active:<userId>"` — 값이 `$SCHEDULE_ID`임을 확인
  5. `redis-cli SISMEMBER "queue:active-schedules" "$SCHEDULE_ID"` — 1 반환 확인
- **기대 결과**: HTTP 200, `{"status":"WAITING","rank":1,"estimatedWaitTime":1,"token":null}` (rank는 1-based, estimatedWaitTime은 ceil 계산값)
- **검증 포인트**: 응답 `status=WAITING`, `token=null`, `rank>=1`; Redis `queue:{scheduleId}` Sorted Set에 userId 존재; `queue:active:{userId}` 키 존재 및 TTL ≤ 600초; `queue:active-schedules` SET에 scheduleId 포함

---

### TC-QUEUE-002 — 동일 회차 중복 진입 멱등성: 기존 rank 반환, 순서 불변

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-001, REQ-QUEUE-011
- **분류**: 멱등성
- **우선순위**: P0
- **사전조건**: 동일 userId로 이미 1회 진입 완료 상태 (TC-QUEUE-001 이후)
- **실행 단계**:
  1. 동일 userId, 동일 scheduleId로 `POST /queue/enter` 재호출 (TC-QUEUE-001과 동일 파라미터)
  2. 응답 rank 값 기록
  3. `redis-cli ZCARD "queue:$SCHEDULE_ID"` — 멤버 수 여전히 1인지 확인
  4. `redis-cli ZRANK "queue:$SCHEDULE_ID" <userId>` — score 변경 없이 기존 rank(0) 유지 확인
  5. `redis-cli TTL "queue:active:<userId>"` — TTL이 갱신(≥ 이전 값)되었는지 확인
- **기대 결과**: HTTP 200, `status=WAITING`, 첫 번째 응답과 동일한 rank 반환; Sorted Set 멤버 수 불변(ZADD NX 보장)
- **검증 포인트**: Sorted Set ZCARD=1 유지; 동일 rank 반환; `queue:active:{userId}` TTL이 갱신됨(EXPIRE 재호출 확인)

---

### TC-QUEUE-003 — 다중 대기열 제한: 다른 회차 대기 중 진입 시도 → 409 ALREADY_IN_QUEUE

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-011
- **분류**: 엣지, 예외
- **우선순위**: P0
- **사전조건**: userId A가 scheduleId-1에 이미 대기 중 (`queue:active:{userId}` 키 존재)
- **실행 단계**:
  1. userId A로 scheduleId-1에 `POST /queue/enter` → 200 OK 확인
  2. 동일 userId A로 **다른** scheduleId-2에 `POST /queue/enter` 호출
  3. 응답 코드 및 body 확인
  4. `redis-cli ZCARD "queue:$SCHEDULE_ID_2"` — 0 확인 (진입 안 됨)
- **기대 결과**: HTTP 409, `{"code":"ALREADY_IN_QUEUE","message":"이미 대기열에 참여 중입니다."}`
- **검증 포인트**: HTTP 409; `queue:{scheduleId-2}` Sorted Set에 userId 없음; `queue:active:{userId}` 값이 여전히 scheduleId-1

---

### TC-QUEUE-004 — 대기열 용량 한계값(50,000명) 초과 시 503 QUEUE_FULL

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-006
- **분류**: 경계값, 예외
- **우선순위**: P0
- **사전조건**: 테스트 전용 scheduleId, `queue.max-capacity` 설정값 확인(기본 50000); 테스트 편의상 `application-test.yml`에서 `queue.max-capacity: 2` 로 설정하거나 Redis에 직접 더미 멤버 49,999개 삽입
- **실행 단계**:
  1. (간소화 방법) Redis CLI로 더미 데이터 삽입:
     ```bash
     # maxCapacity-1명을 미리 채움 (테스트용 낮은 capacity 설정 권장)
     for i in $(seq 1 49999); do
       redis-cli ZADD "queue:$SCHEDULE_ID" $((1000+i)) "dummy-user-$i" > /dev/null
     done
     ```
  2. 50,000번째 사용자로 `POST /queue/enter` → 200 확인 (정확히 50,000번째)
  3. 50,001번째 사용자로 `POST /queue/enter` → 503 확인
  4. `redis-cli ZCARD "queue:$SCHEDULE_ID"` — 50,000 이하 유지 확인
- **기대 결과**: 50,000번째: HTTP 200; 50,001번째: HTTP 503, `{"code":"QUEUE_FULL","message":"대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."}`
- **검증 포인트**: HTTP 503; Sorted Set ZCARD = 50,000 (초과 진입 없음); `queue:active-schedules`에 신규 userId 추가 없음

---

### TC-QUEUE-005 — 대기열 상태 조회: WAITING → ACTIVE 전환 후 token(qr_xxx) 발급 확인

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-002, REQ-QUEUE-004, REQ-QUEUE-005
- **분류**: 정상
- **우선순위**: P0
- **사전조건**: userId가 대기열에 진입(TC-QUEUE-001), 배치 승인 스케줄러가 실행될 수 있는 환경
- **실행 단계**:
  1. `GET /queue/status?scheduleId=$SCHEDULE_ID` 호출 → `status=WAITING` 확인
  2. `QueueService.batchApprove(scheduleId)` 직접 호출(통합테스트) 또는 1초 대기 후 자동 승인 대기
  3. `GET /queue/status?scheduleId=$SCHEDULE_ID` 재호출
  4. 응답의 `token` 값(`qr_` 접두사) 기록
  5. `redis-cli GET "queue:token:<token>"` — JSON 페이로드 확인
  6. `redis-cli TTL "queue:token:<token>"` — TTL ≤ 600초 확인
  7. `redis-cli GET "queue:user-token:<userId>:<scheduleId>"` — token 값 일치 확인
- **기대 결과**: HTTP 200, `{"status":"ACTIVE","rank":0,"estimatedWaitTime":0,"token":"qr_..."}`, `Cache-Control: no-store` 헤더 포함
- **검증 포인트**: `token` 필드가 `qr_` 접두사로 시작; `queue:token:{token}` 키 존재 및 JSON에 `userId`, `scheduleId`, `issuedAt` 포함; `queue:user-token:{userId}:{scheduleId}` 키 존재; 두 키 모두 TTL 590~600초

---

### TC-QUEUE-006 — 상태 조회 Rate Limit: 15회/분 초과 시 429 RATE_LIMIT_EXCEEDED

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-008
- **분류**: 경계값, 예외
- **우선순위**: P0
- **사전조건**: 동일 userId로 rate limit 카운터가 초기화된 상태 (Redis flush 또는 신규 userId 사용)
- **실행 단계**:
  1. 동일 userId로 `GET /queue/status?scheduleId=$SCHEDULE_ID` 를 15회 연속 호출 → 모두 200 또는 404(대기열 없음) 확인
  2. 16번째 호출 실행
  3. `redis-cli GET "rate:queue-status:<userId>"` — 카운터 값 확인
  4. `redis-cli TTL "rate:queue-status:<userId>"` — 60초 이하 TTL 확인
- **기대 결과**: 1~15회: HTTP 200(또는 404); 16회: HTTP 429, `{"code":"RATE_LIMIT_EXCEEDED","message":"요청이 너무 많습니다. 잠시 후 다시 시도해주세요."}`
- **검증 포인트**: HTTP 429; `rate:queue-status:{userId}` 값 = 16; TTL이 설정됨(고정 윈도우 방식, 최초 INCR 시점부터 60초); 17번째 이후 호출도 계속 429

---

### TC-QUEUE-007 — Rate Limit Fail-open: Redis 장애 시 상태 조회 요청 허용

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-008
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: 통합테스트 환경에서 StringRedisTemplate을 Mock으로 교체하거나, Valkey 컨테이너를 일시 중단(pause)하여 Redis 장애 시뮬레이션
- **실행 단계**:
  1. (통합테스트 방식) `rateLimitScript` 실행 시 예외를 throw하도록 MockkBean 설정
  2. `GET /queue/status?scheduleId=$SCHEDULE_ID` 호출
  3. 응답 코드 확인
  4. Micrometer 카운터 `queue.ratelimit.failopen.total` 값 확인: `curl http://localhost:9083/actuator/prometheus | grep failopen`
- **기대 결과**: Redis 장애 시에도 HTTP 200(또는 상태에 맞는 응답); 차단되지 않음(fail-open); `queue.ratelimit.failopen.total` 카운터 ≥ 1
- **검증 포인트**: 요청이 429가 아닌 정상 응답 코드로 처리됨; Prometheus 메트릭 `queue_ratelimit_failopen_total` 증가 확인

---

### TC-QUEUE-008 — 배치 승인 Lua 스크립트 원자성: 동시 배치 실행 시 중복 토큰 발급 없음

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-005
- **분류**: 동시성
- **우선순위**: P0
- **사전조건**: 동일 scheduleId에 10명 대기 중, 배치 스케줄러 비활성화 (`@MockitoBean BatchApproveScheduler`)
- **실행 단계**:
  1. 10명 사용자를 순차 진입 처리 (각 `POST /queue/enter`)
  2. `QueueService.batchApprove(scheduleId)`를 **2개의 스레드에서 동시 호출**:
     ```kotlin
     val f1 = CompletableFuture.supplyAsync { queueService.batchApprove(scheduleId) }
     val f2 = CompletableFuture.supplyAsync { queueService.batchApprove(scheduleId) }
     val total = f1.get() + f2.get()
     ```
  3. 두 호출의 반환값 합계 확인
  4. `redis-cli ZCARD "queue:$SCHEDULE_ID"` — 0 확인
  5. `redis-cli KEYS "queue:user-token:*"` 로 토큰 키 수 확인 (정확히 10개)
- **기대 결과**: 두 호출 반환값 합계 = 10 (한 호출이 10, 나머지가 0 또는 분배); 토큰 키 정확히 10개; 중복 토큰 없음
- **검증 포인트**: `ZCARD=0`; user-token 키 = 10개; 동일 userId에 대한 토큰이 1개만 존재; `queue.batch.approved.total` 카운터 = 10

---

### TC-QUEUE-009 — 배치 승인 후 active-schedules SREM: 대기열 소진 시 자동 제거

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-005
- **분류**: 엣지
- **우선순위**: P1
- **사전조건**: 1명만 대기 중인 scheduleId, `queue.batch.size=10` 기본값
- **실행 단계**:
  1. userId 1명 진입 → `redis-cli SISMEMBER "queue:active-schedules" "$SCHEDULE_ID"` = 1 확인
  2. `QueueService.batchApprove(scheduleId)` 호출 → 반환값 = 1 확인
  3. `redis-cli SISMEMBER "queue:active-schedules" "$SCHEDULE_ID"` — 0 확인 (SREM됨)
  4. `redis-cli ZCARD "queue:$SCHEDULE_ID"` — 0 확인
- **기대 결과**: batchApprove 반환값 = 1; `queue:active-schedules`에서 scheduleId 제거됨; Sorted Set 비어 있음
- **검증 포인트**: `SISMEMBER=0`; `ZCARD=0`; 이후 BatchApproveScheduler 실행 시 해당 scheduleId를 처리 대상에서 제외(getActiveScheduleIds 결과에 없음)

---

### TC-QUEUE-010 — 이미 배치 승인된 사용자의 재진입 시도 → 409 ALREADY_APPROVED

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-001
- **분류**: 엣지, 예외
- **우선순위**: P1
- **사전조건**: userId가 배치 승인 완료 상태: `queue:active:{userId}`는 존재하지만 `queue:{scheduleId}` Sorted Set에서 제거된 상태
- **실행 단계**:
  1. userId로 진입 후 배치 승인 실행 (`batchApprove`)
  2. 동일 userId, 동일 scheduleId로 `POST /queue/enter` 재호출
  3. Lua 스크립트 응답 코드 4 (ALREADY_APPROVED) 경로 확인
- **기대 결과**: HTTP 409, `{"code":"ALREADY_APPROVED","message":"이미 대기열 승인이 완료되었습니다. 좌석 선택 페이지로 이동해주세요."}`
- **검증 포인트**: HTTP 409; `queue:active:{userId}` 존재 유지; Sorted Set에 재추가 없음

---

### TC-QUEUE-011 — 대기열 이탈(WAITING 상태): Redis 키 3종 일괄 삭제

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-003
- **분류**: 정상, 엣지
- **우선순위**: P0
- **사전조건**: userId가 WAITING 상태로 대기 중 (TC-QUEUE-001 완료 후)
- **실행 단계**:
  1. 이탈 전 키 존재 확인:
     ```bash
     redis-cli EXISTS "queue:$SCHEDULE_ID"           # Sorted Set
     redis-cli ZRANK "queue:$SCHEDULE_ID" "<userId>" # 멤버 존재
     redis-cli EXISTS "queue:active:<userId>"         # active 키
     ```
  2. `DELETE /queue/leave?scheduleId=$SCHEDULE_ID` 호출
  3. 이탈 후 키 삭제 확인:
     ```bash
     redis-cli ZRANK "queue:$SCHEDULE_ID" "<userId>" # nil
     redis-cli EXISTS "queue:active:<userId>"          # 0
     ```
- **기대 결과**: HTTP 200, `{"message":"Removed from queue"}`; Sorted Set에서 userId 제거; `queue:active:{userId}` 키 삭제
- **검증 포인트**: `ZRANK=nil`; `EXISTS queue:active:{userId}=0`; `queue:user-token:{userId}:{scheduleId}` 미존재(토큰 없는 WAITING 상태)

---

### TC-QUEUE-012 — 대기열 이탈(ACTIVE 상태): token 관련 키 3종 일괄 삭제

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-003
- **분류**: 엣지
- **우선순위**: P0
- **사전조건**: userId가 배치 승인 완료(ACTIVE) 상태: `queue:active:{userId}`, `queue:user-token:{userId}:{scheduleId}`, `queue:token:{token}` 키 모두 존재
- **실행 단계**:
  1. 진입 후 배치 승인 실행
  2. `GET /queue/status` 로 token 값 획득 (`qr_xxx`)
  3. 세 키 존재 확인
  4. `DELETE /queue/leave?scheduleId=$SCHEDULE_ID` 호출
  5. 세 키 모두 삭제 확인:
     ```bash
     redis-cli EXISTS "queue:active:<userId>"
     redis-cli EXISTS "queue:user-token:<userId>:<scheduleId>"
     redis-cli EXISTS "queue:token:<token>"
     ```
- **기대 결과**: HTTP 200, `{"message":"Removed from queue"}`; 세 키 모두 삭제
- **검증 포인트**: `EXISTS queue:active:{userId}=0`; `EXISTS queue:user-token:...=0`; `EXISTS queue:token:{token}=0`

---

### TC-QUEUE-013 — 이탈 후 재이탈 시도 → 404 NOT_IN_QUEUE

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-003
- **분류**: 멱등성, 예외
- **우선순위**: P1
- **사전조건**: userId가 이탈 완료 상태 (TC-QUEUE-011 이후)
- **실행 단계**:
  1. 이미 이탈한 userId로 `DELETE /queue/leave?scheduleId=$SCHEDULE_ID` 재호출
  2. 응답 코드 확인
- **기대 결과**: HTTP 404, `{"code":"NOT_IN_QUEUE","message":"대기열에 참여하고 있지 않습니다."}`
- **검증 포인트**: HTTP 404; 추가 Redis 변경 없음

---

### TC-QUEUE-014 — 이탈 후 재진입 시 WAITING 상태로 성공, rank=1

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-001, REQ-QUEUE-003
- **분류**: 엣지
- **우선순위**: P1
- **사전조건**: userId가 이탈 완료, 대기열이 비어 있는 상태
- **실행 단계**:
  1. `POST /queue/enter` 재호출 (동일 userId, 동일 scheduleId)
  2. `redis-cli ZCARD "queue:$SCHEDULE_ID"` — 1 확인
  3. `redis-cli GET "queue:active:<userId>"` — scheduleId 값 확인
- **기대 결과**: HTTP 200, `{"status":"WAITING","rank":1,...}`; Sorted Set 멤버 다시 등록
- **검증 포인트**: HTTP 200; `ZCARD=1`; `queue:active:{userId}` 값 = scheduleId

---

### TC-QUEUE-015 — queue:status 조회 시 대기열 미등록 → 404 NOT_IN_QUEUE

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-002
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: 해당 userId가 진입한 적 없는 신규 UUID; Redis에 관련 키 없음
- **실행 단계**:
  1. 신규 userId(진입 이력 없음)로 `GET /queue/status?scheduleId=$SCHEDULE_ID` 호출
  2. Lua 스크립트 반환 코드 2 (NOT_IN_QUEUE) 경로 확인
- **기대 결과**: HTTP 404, `{"code":"NOT_IN_QUEUE","message":"대기열에 참여하고 있지 않습니다."}`
- **검증 포인트**: HTTP 404; 새로운 Redis 키 생성 없음

---

### TC-QUEUE-016 — 관리자 통계 API: ADMIN 권한 접근 성공, USER 권한 접근 403

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-007
- **분류**: 보안
- **우선순위**: P1
- **사전조건**: ADMIN JWT 및 USER JWT 각각 발급 완료, 1개 이상 scheduleId 대기 중
- **실행 단계**:
  1. USER JWT로 `GET /queue/admin/stats` 호출 → 403 확인
  2. JWT 없이 `GET /queue/admin/stats` 호출 → 401 확인
  3. ADMIN JWT로 `GET /queue/admin/stats` 호출
  4. 응답 JSON 구조 검증
- **기대 결과**:
  - USER JWT: HTTP 403
  - 토큰 없음: HTTP 401
  - ADMIN JWT: HTTP 200, `{"totalWaiting":N,"scheduleStats":[{"scheduleId":"...","waitingCount":N,"activeCount":N,"tps":10}],"batchApprovalRate":10,"batchIntervalSeconds":1,"throughputPerMinute":600}`
- **검증 포인트**: HTTP 200; `scheduleStats` 배열에 실제 대기 중인 scheduleId 포함; `waitingCount` 값이 `redis-cli ZCARD "queue:$SCHEDULE_ID"` 와 일치

---

### TC-QUEUE-017 — 관리자 통계 API: KEYS 명령 미사용, SMEMBERS + ZCARD(O(1)) 전용

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-007
- **분류**: 보안
- **우선순위**: P0
- **사전조건**: Valkey의 커맨드 모니터링 가능 환경 (`redis-cli MONITOR` 또는 Redis slow log)
- **실행 단계**:
  1. 별도 터미널에서 `redis-cli MONITOR` 실행
  2. ADMIN JWT로 `GET /queue/admin/stats` 호출
  3. MONITOR 출력에서 `KEYS` 명령 등장 여부 확인
  4. `SMEMBERS queue:active-schedules` 와 각 scheduleId별 `ZCARD queue:{scheduleId}` 호출 확인
- **기대 결과**: MONITOR 출력에 `KEYS` 명령 없음; `SMEMBERS queue:active-schedules` 1회 호출 확인; 각 회차별 `ZCARD` 호출 확인; `SCAN` 명령은 `countActiveUsers` 내부에서만 허용
- **검증 포인트**: `KEYS` 명령 미존재; `SMEMBERS` + `ZCARD` 패턴 확인

---

### TC-QUEUE-018 — scheduleId 없는 요청: scheduleId=null → 400 INVALID_INPUT

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-001
- **분류**: 예외, 경계값
- **우선순위**: P1
- **사전조건**: queue-service 실행 중
- **실행 단계**:
  1. `scheduleId` 필드를 누락한 body로 `POST /queue/enter` 호출:
     ```bash
     curl -s -X POST http://localhost:8083/queue/enter \
       -H "X-User-Id: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
       -H "X-User-Role: USER" \
       -H "Content-Type: application/json" \
       -d "{}"
     ```
  2. `scheduleId: null` 명시 body로 호출:
     ```bash
     curl -s -X POST http://localhost:8083/queue/enter \
       -H "X-User-Id: $(uuidgen | tr '[:upper:]' '[:lower:]')" \
       -H "X-User-Role: USER" \
       -H "Content-Type: application/json" \
       -d "{\"scheduleId\": null}"
     ```
- **기대 결과**: 두 경우 모두 HTTP 400; 응답에 `code` 또는 `message` 포함
- **검증 포인트**: HTTP 400; Redis에 관련 키 생성 없음

---

### TC-QUEUE-019 — 회차 판매 종료 후 대기열 진입 시도 → 400 TICKET_SALE_ENDED

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-001
- **분류**: 예외
- **우선순위**: P1
- **사전조건**: EventServiceClient Mock이 `sellable=false, reason="TICKET_SALE_ENDED"` 반환하도록 설정; 통합테스트에서 `@MockkBean EventServiceClient` 사용
- **실행 단계**:
  1. 통합테스트에서:
     ```kotlin
     every { eventServiceClient.checkSellable(any()) } returns
       EventServiceClient.SellableResponse(sellable = false, reason = "TICKET_SALE_ENDED")
     ```
  2. `POST /queue/enter` 호출
  3. 응답 코드 확인
  4. `redis-cli ZCARD "queue:$SCHEDULE_ID"` — 0 확인 (진입 안 됨)
- **기대 결과**: HTTP 400, `{"code":"TICKET_SALE_ENDED","message":"티켓 판매가 종료되었습니다."}`
- **검증 포인트**: HTTP 400; Sorted Set에 멤버 없음; `queue:active:{userId}` 키 미생성; `schedule:sellable:{scheduleId}` 캐시 키에 `sellable=false` 결과가 30초 TTL로 저장됨

---

### TC-QUEUE-020 — 동시 진입 폭주(100 스레드) 순서 정합성: ZADD NX race condition 없음

- [ ] 미실행
- **관련 REQ**: REQ-QUEUE-001, REQ-QUEUE-009
- **분류**: 동시성, 성능
- **우선순위**: P0
- **사전조건**: 100개의 고유 userId 준비, 배치 스케줄러 비활성화, 충분한 maxCapacity(50000)
- **실행 단계**:
  1. 통합테스트에서 100개 스레드 동시 진입:
     ```kotlin
     val userIds = (1..100).map { UUID.randomUUID() }
     val latch = CountDownLatch(1)
     val futures = userIds.map { uid ->
       CompletableFuture.supplyAsync {
         latch.await()
         queueService.enterQueue(uid, scheduleId)
       }
     }
     latch.countDown()
     val results = futures.map { it.get() }
     ```
  2. `redis-cli ZCARD "queue:$SCHEDULE_ID"` — 정확히 100 확인
  3. 각 userId의 rank 중복 여부 확인: `redis-cli ZRANGE "queue:$SCHEDULE_ID" 0 -1 WITHSCORES` 로 멤버 100개, 고유 score 확인
  4. 모든 userId에 대해 `GET /queue/status` 응답 rank 범위 1~100 확인
- **기대 결과**: 모든 100개 요청 성공(HTTP 200 또는 Java 레벨 정상 반환); Sorted Set ZCARD=100; rank 1~100 고유값 배정; 중복 rank 없음
- **검증 포인트**: `ZCARD=100`; ZRANGE 멤버 100개 모두 고유; rank 중복 없음 (score=진입 timestamp이므로 동일 ms 진입 시 순서는 Redis 내부 결정이나 멤버 고유성은 보장); 진입 오류 없음
