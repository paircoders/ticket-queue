# 📌 프로젝트 요구사항 명세서

## 문서 개요

이 문서는 **콘서트/공연 티켓팅 시스템**의 MSA 아키텍처 기반 요구사항을 정의합니다.

### 요구사항 통계

| 분류 | 개수 | 비율 |
|------|------|------|
| 기능 요구사항 | 69 | 63.3% |
| 비기능 요구사항 | 40 | 36.7% |
| **합계** | **109** | **100%** |

| 우선순위 | 개수 | 비율 |
|----------|------|------|
| 필수 | 93 | 85.3% |
| 선택 | 16 | 14.7% |

### MSA 구성

- **API Gateway (A개발자)**: Spring Cloud Gateway 기반 진입점
- **Event Service (A개발자)**: 공연/공연장/홀 관리
- **Queue Service (A개발자)**: 대기열
- **User Service (B개발자)**: 회원/인증 기능
- **Reservation Service (B개발자)**: 예매 관리
- **Payment Service (B개발자)**: 결제 처리

---

## 1. 회원 / 인증 (AUTH)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-AUTH-001 | 회원가입 | 이메일, 비밀번호, 이름, 전화번호를 입력받아 신규 회원 등록 | B개발자 | 기능 | 필수 | |
| REQ-AUTH-002 | 로그인 | 이메일, 비밀번호 검증 후 JWT Access/Refresh Token 발급 | B개발자 | 기능 | 필수 | |
| REQ-AUTH-003 | 로그아웃 | 세션 종료 처리 | B개발자 | 기능 | 필수 | |
| REQ-AUTH-004 | 프로필 조회 | 로그인한 사용자의 프로필 정보 조회 | B개발자 | 기능 | 선택 | |
| REQ-AUTH-005 | 프로필 수정 | 이름, 전화번호 수정 | B개발자 | 기능 | 선택 | |
| REQ-AUTH-006 | 토큰 갱신 | Refresh Token으로 새 Access Token 발급 | B개발자 | 기능 | 필수 | |
| REQ-AUTH-007 | 비밀번호 암호화 | BCrypt 암호화 적용 | B개발자 | 기능 | 필수 | 보안 필수 |
| REQ-AUTH-008 | 이메일 중복 확인 | 회원가입 시 이메일 중복 검사 | B개발자 | 기능 | 필수 | 409 Conflict |
| REQ-AUTH-009 | 권한 기반 접근 제어 | USER / ADMIN 권한 구분 | B개발자 | 기능 | 필수 | RBAC |
| REQ-AUTH-010 | JWT 토큰 만료 시간 | Access 1시간 / Refresh 7일 | B개발자 | 비기능 | 필수 | 보안 정책 |
| REQ-AUTH-011 | 개인정보 컬럼 암호화 | 이메일, 이름, 전화번호 암호화 저장 | B개발자 | 기능 | 선택 | AES-256 |
| REQ-AUTH-012 | 이메일 인증 | 인증번호 발송 및 검증 | B개발자 | 기능 | 선택 | SMTP |
| REQ-AUTH-013 | 회원 탈퇴 | DB에서 탈퇴 여부 업데이트 | B개발자 | 기능 | 필수 | Soft Delete |
| REQ-AUTH-014 | 비밀번호 변경 | 로그인 상태에서 비밀번호 변경 | B개발자 | 기능 | 선택 | |
| REQ-AUTH-015 | 비밀번호 찾기 | 아이디 인증 후 비밀번호 재설정 | B개발자 | 기능 | 선택 | |

---

## 2. 공연 관리 (EVENT)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-EVT-001 | 공연 생성 (관리자) | 제목, 아티스트, 일시, 공연장, 좌석 정보 등 입력 | A개발자 | 기능 | 필수 | 데이터 생성 수단 필요 |
| REQ-EVT-002 | 공연 정보 수정 | 판매 시작 전: 모든 필드 수정 가능. 판매 시작 후: 좌석/가격 수정 불가(제목/설명만) | A개발자 | 기능 | 선택 | 예매 건 있을 시 일시 변경 시 알림 |
| REQ-EVT-003 | 공연 삭제 | Soft Delete 방식. 예매 건 있으면 삭제 불가(409), 없으면 deletedAt 업데이트 | A개발자 | 기능 | 선택 | 삭제된 공연 목록 제외 |
| REQ-EVT-004 | 공연 목록 조회 | 페이징(기본 20개, 최대 100개), 필터링(status, startDate, endDate, venueId), 정렬(startDate, createdAt, title), 검색(title, artist - 부분 일치) 지원 | A개발자 | 기능 | 필수 | QueryDSL 사용 |
| REQ-EVT-005 | 공연 상세 조회 | 상세 정보 및 잔여 좌석 수 표시. Redis 캐싱(TTL 30초), 잔여 좌석은 실시간 DB 조회 | A개발자 | 기능 | 필수 | P95 < 100ms |
| REQ-EVT-006 | 공연 좌석 정보 조회 | 등급별(VIP/S/A/B) 그룹핑하여 좌석 수, 잔여 수, 가격 조회. Redis 캐싱(TTL 10초), HOLD 상태는 AVAILABLE로 표시 | A개발자 | 기능 | 필수 | P95 < 300ms |
| REQ-EVT-007 | 공연 상태 관리 | UPCOMING / ONGOING / ENDED | A개발자 | 기능 | 필수 | 공연 상태 없이 필터링/검증 불가 |
| REQ-EVT-008 | 좌석 상태 관리 | AVAILABLE / HOLD / SOLD. 전이: AVAILABLE→HOLD(예매), HOLD→SOLD(결제), HOLD/SOLD→AVAILABLE(취소). Kafka 이벤트로만 상태 전이 수행 | A개발자 | 기능 | 필수 | 좌석 상태는 핵심 비즈니스 로직 |
| REQ-EVT-009 | 좌석 등급 관리 | VIP / S / A / B | A개발자 | 기능 | 필수 | REQ-EVT-006이 등급별 조회를 요구함 |
| REQ-EVT-010 | 공연장 생성 (관리자) | 공연장명, 주소 등 등록 | A개발자 | 기능 | 필수 | 공연장 데이터 생성 수단 필요 |
| REQ-EVT-011 | 공연장 수정 | 공연장 정보 수정 | A개발자 | 기능 | 선택 | |
| REQ-EVT-012 | 공연장 삭제 | 공연장 정보 삭제 | A개발자 | 기능 | 선택 | |
| REQ-EVT-013 | 홀 정보 생성 | 홀명, 좌석 정보 등록 | A개발자 | 기능 | 필수 | 홀 데이터 생성 수단 필요 |
| REQ-EVT-014 | 홀 정보 수정 | 홀 정보 수정 | A개발자 | 기능 | 선택 | |
| REQ-EVT-015 | 홀 정보 삭제 | 홀 정보 삭제 | A개발자 | 기능 | 선택 | |
| REQ-EVT-016 | Kafka Consumer - 예매 이벤트 처리 | reservation-events 토픽에서 예매 생성/취소/결제 완료 이벤트 수신하여 좌석 상태 업데이트. Consumer Group: event-service-group, 이벤트 타입: RESERVATION_CREATED/PAYMENT_COMPLETED/RESERVATION_CANCELLED, Idempotency: 이벤트 ID 기반 중복 방지(Redis TTL 24시간), Partition Key: seatId로 순서 보장 | A개발자 | 기능 | 필수 | 3회 재시도 후 DLQ |
| REQ-EVT-017 | Redis 캐싱 전략 | 조회 성능 향상. 공연 목록: event:list:{filterHash} (TTL 60초), 공연 상세: event:detail:{eventId} (TTL 30초), 좌석 상태: event:seats:{eventId} (TTL 10초), 공연장/홀: venue/hall:{id} (TTL 24시간). Cache-Aside 패턴 | A개발자 | 비기능 | 필수 | Kafka 이벤트 수신 시 캐시 무효화 |
| REQ-EVT-018 | QueryDSL 동적 쿼리 | 복잡한 필터링/검색을 위한 타입 안전 쿼리. REQ-EVT-004(공연 목록 조회)에 적용. 단순 조회는 Spring Data JPA 사용 | A개발자 | 기능 | 필수 | 다중 필터/정렬/검색 조합 |
| REQ-EVT-019 | 좌석 초기화 로직 | 공연 생성 시(REQ-EVT-001) hallId 기반으로 좌석 자동 생성. Hall 좌석 템플릿(row, number, grade, price)을 Seat 테이블에 INSERT. 초기 상태: AVAILABLE | A개발자 | 기능 | 필수 | Batch Insert (크기 100) |
| REQ-EVT-020 | 캐시 무효화 이벤트 처리 | 좌석 상태 변경 시 관련 캐시 삭제. Kafka 이벤트 수신 시: event:seats:{eventId}, 공연 수정 시: event:detail:{eventId}, event:list:*, 공연장/홀 수정 시: venue/hall 캐시 삭제 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-021 | 성능 목표 - 응답 시간 | 엔드포인트별 SLO. GET /events: P95<200ms (캐시 히트율 80%+), GET /events/{id}: P95<100ms (캐시 90%+), GET /events/{id}/seats: P95<300ms (캐시 70%+), POST /admin/events: P95<1000ms | A개발자 | 비기능 | 필수 | |
| REQ-EVT-022 | DB Read Replica 사용 | 읽기 트래픽 분산. 조회 쿼리: Read Replica (REQ-EVT-004,005,006), 쓰기 쿼리: Primary DB (Kafka Consumer, Admin API). @Transactional(readOnly=true) 사용 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-023 | 좌석 상태 업데이트 동시성 제어 | Kafka Consumer에서 동일 좌석 동시 업데이트 방지. DB Optimistic Lock (version 컬럼), 실패 시 재처리(최대 3회), Partition Key=seatId로 순서 보장 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-024 | Dead Letter Queue 처리 | 처리 실패 이벤트 보관. DLQ 토픽: reservation-events-dlq, 전송 조건: 3회 재시도 실패, 모니터링: CloudWatch Metric/Alarm | A개발자 | 비기능 | 필수 | 수동 재처리 API (선택) |
| REQ-EVT-025 | 공연 검색 Full-text Search | ElasticSearch 연동으로 검색 성능 향상 (향후 고려) | A개발자 | 기능 | 선택 | 초기: LIKE 쿼리, 데이터 증가 시 도입 |

---

## 3. 대기열 (QUEUE)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-QUEUE-001 | 대기열 진입 | 예매하기 버튼 클릭 시 진입 (공연 상세 페이지 접근만으로는 진입하지 않음). userId+eventId 조합으로 Redis Sorted Set ZADD NX. 중복 시도 시 기존 대기 순서 반환 (409 아님). 멀티 디바이스: 동일 userId는 하나의 대기 순서 유지 | A개발자 | 기능 | 필수 | 중복 진입 방지 |
| REQ-QUEUE-002 | 대기열 상태 조회 | REST API 폴링(5초마다 권장, WebSocket 미사용). 응답: queuePosition(현재 순서), estimatedWaitSeconds(예상 대기 시간=(position/10)*1초), totalInQueue(대기열 총 인원), tokenStatus(queued/admitted/expired) | A개발자 | 기능 | 필수 | Rate Limiting: 60회/분 |
| REQ-QUEUE-003 | 대기열 만료 처리 | 대기 중 만료: Sorted Set 멤버 TTL 없음 (수동 제거). 허가 후 만료: Queue Token TTL 10분. 비활성 사용자: 5분간 상태 조회 없으면 제거 (선택 구현) | A개발자 | 기능 | 필수 | Redis TTL |
| REQ-QUEUE-004 | Queue Token 발급 및 검증 | 이중 Token 모델 적용(REQ-QUEUE-016). 1) Reservation Token (qr_xxx): 발급-Queue Service (배치 스케줄러), 검증 주체-Reservation Service, 검증 후 즉시 삭제(1회용). 2) Payment Token (qp_xxx): 발급-Reservation Service (예매 성공 시), 검증 주체-Payment Service, 검증 후 즉시 삭제(1회용). Gateway는 X-Reservation-Token, X-Payment-Token 헤더 전달만 수행. 검증 실패 시 401 Unauthorized. 재발급: REQ-QUEUE-018 참조 | A개발자 | 기능 | 필수 | 이중 토큰, REQ-QUEUE-016 연계 |
| REQ-QUEUE-005 | 배치 승인 처리 (10명/1초) | 1초마다 스케줄러 실행 (@Scheduled(fixedRate=1000)). 10명 승인 (ZRANGE 0-9). 로직: Sorted Set 조회 → Queue Token 발급 → ZREM. Lua 스크립트로 원자적 실행 | A개발자 | 기능 | 필수 | 시간당 36,000명 처리 |
| REQ-QUEUE-006 | Redis Sorted Set 구현 상세 | Key: queue:event:{eventId}, Member: {userId}, Score: Epoch Millis + Random 4자리 (동점 방지). 진입: ZADD NX, 순서: ZRANK, 총 인원: ZCARD, 승인: ZRANGE 0-9, 제거: ZREM | A개발자 | 비기능 | 필수 | |
| REQ-QUEUE-007 | 중복 진입 방지 | ZADD NX 옵션 사용. 중복 시: 기존 ZRANK 반환, HTTP 200. 브라우저 탭 여러 개: 동일 userId이므로 하나의 대기 순서 공유. 페이지 새로고침: 대기 순서 유지 | A개발자 | 기능 | 필수 | |
| REQ-QUEUE-008 | 사용자 연결 관리 | Heartbeat: 상태 조회 5초마다. 마지막 조회 시간: Redis Hash queue:heartbeat:{eventId}. 정리 스케줄러: 5분마다 실행, 5분 이상 조회 없는 사용자 제거 | A개발자 | 기능 | 선택 | 재진입 가능 (맨 뒤로) |
| REQ-QUEUE-009 | Reservation Service 연동 | 클라이언트: POST /reservations Header X-Queue-Token:{tokenId}. Gateway: 헤더 그대로 전달. Reservation: Redis에서 queue:token:{userId}:{eventId} 검증, 성공 시 토큰 삭제(1회용), 실패 시 401 | A개발자 | 기능 | 필수 | 토큰 재사용 방지 |
| REQ-QUEUE-010 | 대기열 용량 제한 | 공연별 최대 50,000명. 진입 시 ZCARD 확인. 초과 시 503 Service Unavailable. 환경 변수: QUEUE_MAX_SIZE=50000 | A개발자 | 기능 | 필수 | 과부하 방지 |
| REQ-QUEUE-011 | Queue Token TTL 관리 | TTL 10분 (600초). 만료 시: Reservation에서 401. 만료 후: 대기열 재진입 필요 (순서는 맨 뒤). 만료 알림: 클라이언트에서 카운트다운 표시 | A개발자 | 기능 | 필수 | |
| REQ-QUEUE-012 | 대기열 모니터링 API | GET /admin/queue/events/{eventId}/stats. 응답: 대기 인원, 시간당 처리 인원(36,000명), 예상 소진 시간. 권한: ADMIN 역할 필요 | A개발자 | 기능 | 필수 | |
| REQ-QUEUE-013 | 대기열 긴급 제어 | POST /admin/queue/events/{eventId}/pause: 배치 처리 중지. POST .../resume: 재개. Redis Flag: queue:paused:{eventId}. 스케줄러에서 Flag 확인 후 처리 스킵 | A개발자 | 기능 | 선택 | 장애 시 일시 중지 |
| REQ-QUEUE-014 | Rate Limiting - 대기열 조회 | GET /queue/status: 사용자당 60회/분 (1초에 1회). API Gateway에서 적용 (REQ-GW-006 연계). 초과 시 429 Too Many Requests | A개발자 | 비기능 | 필수 | 상태 조회 과부하 방지 |
| REQ-QUEUE-015 | 대기열 성능 목표 | POST /queue/enter: P95<100ms, GET /queue/status: P95<50ms, 처리량: 시간당 36,000명 (10명/초*3600초), Redis 가용성: 99.9% (ElastiCache Multi-AZ) | A개발자 | 비기능 | 필수 | |
| REQ-QUEUE-016 | 이중 Queue Token 모델 | 예매→결제 플로우에서 두 개의 독립적인 Queue Token 사용. 1) Reservation Token: Queue Service 발급(qr_xxx), Key: queue:reservation-token:{userId}:{eventId}, TTL 10분, Reservation Service에서 검증 후 즉시 DEL (1회용). 2) Payment Token: Reservation Service 발급(qp_xxx), Key: queue:payment-token:{userId}:{reservationId}, TTL 10분, Payment Service에서 검증 후 즉시 DEL (1회용). Token 전이: Queue 통과→qr_xxx 발급→Reservation 성공→qr_xxx 소비&qp_xxx 발급→Payment 성공→qp_xxx 소비. 실패 시나리오: Reservation 실패→qr_xxx 삭제됨→대기열 재진입 필요, Payment 실패→qp_xxx 이미 삭제됨→SAGA 보상으로 예매 취소→대기열 재진입 필요 | A개발자, B개발자 | 기능 | 필수 | 완전한 Service Independence, 각 단계별 권한 제어 명확, 재시도 로직 단순 |
| REQ-QUEUE-017 | Payment Token 검증 정책 | Payment Service는 결제 요청 시 Payment Token 검증. 동기 API: X-Payment-Token 헤더에서 qp_xxx 추출, Redis GET queue:payment-token:{userId}:{reservationId}, 검증 성공 시 DEL (1회용), 검증 실패 시 401 Unauthorized. 비동기 Kafka: RESERVATION_CREATED 이벤트에 paymentTokenId 필드 포함(필수), Payment Consumer에서 Redis 검증, 검증 실패 시 DLQ 전송. Idempotency: payment:processed:{eventId} (24시간 TTL). 예외: Admin 결제는 검증 생략(role=ADMIN). 성능: Redis 검증 P95<10ms, 전체 검증 로직 P95<50ms | A개발자, B개발자 | 기능 | 필수 | REQ-QUEUE-016, REQ-PAY-001 연계 |
| REQ-QUEUE-018 | Queue Token 재발급 정책 | 결제 실패 시 사용자 재시도 지원. 재발급 조건: Payment 실패로 SAGA 보상 트랜잭션 실행 시, 예매 상태 CANCELLED로 변경, 사용자 재예매 시도→대기열 맨 뒤로 재진입. 재발급 방식: 자동 재발급 없음(보안상 이유), 사용자는 POST /queue/enter 재호출 필요, ZADD NX로 중복 방지, 새로운 대기 순서 부여(Score=현재 Epoch). UX 가이드: 클라이언트는 401 응답 시 "대기열 재진입 필요" 안내, 자동 리다이렉트로 UX 마찰 최소화 | A개발자 | 기능 | 필수 | REQ-QUEUE-001, REQ-PAY-010 연계 |

---

## 4. 예매 (RESERVATION)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-RSV-001 | 예매 생성 | 좌석 선택 후 예매 요청 | B개발자 | 기능 | 필수 | 이벤트 발행 |
| REQ-RSV-002 | 예매 상세 조회 | 예매 상세 정보 조회 | B개발자 | 기능 | 필수 | |
| REQ-RSV-003 | 예매 취소 | 공연 24시간 전까지 취소 가능 | B개발자 | 기능 | 필수 | |
| REQ-RSV-004 | 사용자 예매 내역 조회 | 마이페이지 예매 목록 | B개발자 | 기능 | 필수 | |
| REQ-RSV-005 | 예매 상태 관리 | PENDING / CONFIRMED / CANCELLED / EXPIRED | B개발자 | 기능 | 필수 | 상태 머신 |
| REQ-RSV-006 | 예매 만료 자동 취소 | 10분 미결제 시 자동 취소 | B개발자 | 기능 | 필수 | 스케줄러 |
| REQ-RSV-007 | Redis 대기열 시스템 | Sorted Set으로 순서 보장 | B개발자 | 비기능 | 필수 | FIFO 보장 |
| REQ-RSV-008 | 분산 락 | 중복 좌석 예매 방지 | B개발자 | 비기능 | 필수 | Redisson |
| REQ-RSV-009 | Kafka Producer | 예매 생성/취소 이벤트 발행 | B개발자 | 기능 | 필수 | reservation-events |
| REQ-RSV-010 | 락 타임아웃 처리 | 락 실패 시 409 응답 | B개발자 | 비기능 | 필수 | Conflict |

---

## 5. 결제 (PAYMENT)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-PAY-001 | 결제 생성 | 예매에 대한 결제 요청 | B개발자 | 기능 | 필수 | |
| REQ-PAY-002 | 결제 확인 | PG 응답 검증 및 완료 처리 | B개발자 | 기능 | 필수 | |
| REQ-PAY-003 | 결제 조회 | 결제 상세 정보 조회 | B개발자 | 기능 | 필수 | |
| REQ-PAY-004 | 사용자 결제 내역 조회 | 마이페이지 결제 목록 | B개발자 | 기능 | 필수 | |
| REQ-PAY-005 | 결제 상태 관리 | PENDING / SUCCESS / FAILED / REFUNDED | B개발자 | 기능 | 필수 | 상태 머신 |
| REQ-PAY-006 | 결제 수단 지원 | CARD | B개발자 | 기능 | 필수 | 신용카드 |
| REQ-PAY-007 | Mock PG API 서버 | 테스트용 가상 결제 | B개발자 | 기능 | 선택 | 개발/테스트 |
| REQ-PAY-008 | Kafka Consumer | 예매 이벤트 수신 후 결제 처리 | B개발자 | 기능 | 필수 | reservation-events |
| REQ-PAY-009 | SAGA 패턴 | 분산 트랜잭션 관리 | B개발자 | 기능 | 필수 | 오케스트레이션 |
| REQ-PAY-010 | 보상 트랜잭션 | 결제 실패 시 예매 취소 | B개발자 | 기능 | 필수 | Rollback |
| REQ-PAY-011 | Circuit Breaker | PG 장애 시 연쇄 장애 방지 | B개발자 | 비기능 | 필수 | Resilience4j |
| REQ-PAY-012 | 결제 타임아웃 | PG 통신 10초 초과 시 | B개발자 | 비기능 | 필수 | Timeout |
| REQ-PAY-013 | 멱등성 키 | 중복 결제 방지 | B개발자 | 비기능 | 필수 | paymentKey |

---

## 6. API GATEWAY (SPRING CLOUD)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-GW-001 | 동적 라우팅 | Path 기반 Predicate로 5개 마이크로서비스 라우팅: /auth, /users → User Service, /events, /venues, /admin/events, /admin/venues → Event Service, /queue → Queue Service, /reservations → Reservation Service, /payments → Payment Service | A개발자 | 기능 | 필수 | RouteLocator |
| REQ-GW-002 | JWT 토큰 검증 필터 | Authorization 헤더의 Bearer Token 검증, 만료/변조 확인 후 userId, role 추출하여 X-User-Id, X-User-Role 헤더로 다운스트림 전달 | A개발자 | 기능 | 필수 | GatewayFilter |
| REQ-GW-003 | 공개 엔드포인트 허용 | 인증 없이 접근 가능한 경로 허용: POST /auth/signup, POST /auth/login, GET /events, GET /events/{id}, GET /events/{id}/seats, GET /venues, GET /venues/{id} | A개발자 | 기능 | 필수 | Predicate 조건 |
| REQ-GW-004 | CORS 설정 | Preflight 요청 처리, Vercel 프론트엔드 Origin 허용, 허용 메서드(GET, POST, PUT, DELETE), 허용 헤더(Authorization, Content-Type), 인증정보 포함(credentials: true) | A개발자 | 기능 | 필수 | CorsConfiguration |
| REQ-GW-005 | IP 기반 Rate Limiting | 엔드포인트별 차등 적용. 읽기 전용(GET /events, /venues): 300회/분, 기타: 100회/분. Redis 기반 Token Bucket 알고리즘, 초과 시 429 Too Many Requests | A개발자 | 비기능 | 필수 | RequestRateLimiter |
| REQ-GW-006 | 사용자 기반 Rate Limiting | 엔드포인트별 세분화. GET /queue/status: 60회/분, POST /reservations, /payments: 20회/분, POST /auth/login, /signup: 10회/분, 기타: 200회/분. IP 제한과 독립적으로 적용 | A개발자 | 비기능 | 필수 | Custom Filter |
| REQ-GW-007 | Circuit Breaker 통합 | Resilience4j 적용. 기존: slidingWindowSize=20, failureRateThreshold=50%, waitDuration=30s. 추가: minimumNumberOfCalls=10, slowCallDurationThreshold=5s, slowCallRateThreshold=60%. Payment Service: waitDuration=60s | A개발자 | 비기능 | 선택 | CircuitBreakerFilter |
| REQ-GW-008 | 글로벌 타임아웃 설정 | 모든 라우트에 기본 타임아웃 30초 적용, 다운스트림 무응답 시 504 Gateway Timeout 응답 | A개발자 | 비기능 | 필수 | connect/response timeout |
| REQ-GW-009 | 라우트별 타임아웃 커스터마이징 | /payments: 60초, /queue/*: 10초 타임아웃 설정. 기타: 30초 (REQ-GW-008 글로벌 기본값) | A개발자 | 비기능 | 선택 | Route metadata |
| REQ-GW-010 | Request ID 전파 | 모든 요청에 고유 X-Request-ID 생성 또는 클라이언트 전달값 재사용, 다운스트림 서비스 및 로그에 전파하여 분산 추적 지원 | A개발자 | 비기능 | 필수 | AddRequestHeader |
| REQ-GW-011 | 요청/응답 로깅 | 요청 메서드, 경로, 응답 상태 코드, 처리 시간 로깅, JSON 구조화 로그, CloudWatch Logs 전송 | A개발자 | 비기능 | 필수 | GlobalFilter |
| REQ-GW-012 | 보안 헤더 추가 | X-Content-Type-Options: nosniff, X-Frame-Options: DENY, X-XSS-Protection: 1; mode=block, Strict-Transport-Security: max-age=31536000 헤더 추가 | A개발자 | 비기능 | 필수 | AddResponseHeader |
| REQ-GW-013 | 게이트웨이 헬스체크 | /actuator/health 엔드포인트 제공, 다운스트림 서비스 상태 집계(User, Event, Reservation, Payment), ALB 타겟 헬스체크 연동 | A개발자 | 기능 | 필수 | Spring Actuator |
| REQ-GW-014 | CloudWatch 메트릭 통합 | 라우트별 요청 수, 응답 시간, 에러율 메트릭 수집, CloudWatch Metrics 전송, 임계값 초과 시 CloudWatch Alarm 트리거 | A개발자 | 비기능 | 필수 | Micrometer |
| REQ-GW-015 | Response Body 압축 | 1KB 이상 응답 데이터 gzip 압축, Accept-Encoding 헤더 확인, Content-Encoding 헤더 추가로 네트워크 대역폭 절감 | A개발자 | 비기능 | 선택 | Compression filter |
| REQ-GW-016 | 로드 밸런싱 전략 | ALB 기반 Round-Robin. Health Check: ALB가 /actuator/health 30초마다 호출. Unhealthy 인스턴스 자동 제외. Sticky Session 미사용 (Stateless) | A개발자 | 비기능 | 필수 | 서비스 인스턴스 간 트래픽 분산 |
| REQ-GW-017 | Service Discovery 설정 | ALB DNS 고정 URL 방식. 각 서비스 ALB DNS를 환경 변수로 주입 (예: SERVICE_EVENT_URL=http://event-service-alb.internal:8080). ECS Auto Scaling 시 ALB가 자동으로 신규 인스턴스 등록 | A개발자 | 비기능 | 필수 | AWS 비용 최소화 |
| REQ-GW-018 | 요청 크기 제한 | 최대 요청 Body: 1MB (대부분), 인증 엔드포인트: 10KB (POST /auth/*), 최대 헤더: 16KB. 초과 시 413 Payload Too Large | A개발자 | 비기능 | 필수 | 대용량 페이로드 공격 방지 |
| REQ-GW-019 | X-Ray 분산 추적 통합 | CloudWatch X-Ray로 요청 추적. Gateway에서 X-Amzn-Trace-Id 헤더 생성/전파, 다운스트림 전달, Segment 기록, 샘플링 10% (비용 절감). REQ-GW-010의 X-Request-ID와 별도 관리 | A개발자 | 비기능 | 필수 | 분산 추적 |
| REQ-GW-020 | Admin 엔드포인트 권한 검증 | 대상: /admin/**, /actuator/** (헬스체크 제외). JWT에서 role 확인, role != ADMIN이면 403 Forbidden, role == ADMIN이면 X-User-Role: ADMIN 헤더 전달. 공개 제외: /actuator/health | A개발자 | 기능 | 필수 | 관리자 전용 API 접근 제어 |
| REQ-GW-021 | Queue Token 헤더 전달 (이중 Token) | 클라이언트: POST /reservations Header: X-Reservation-Token: qr_xxx, POST /payments Header: X-Payment-Token: qp_xxx. Gateway: X-Reservation-Token 헤더를 Reservation Service로 전달, X-Payment-Token 헤더를 Payment Service로 전달, 검증 없이 그대로 전달(각 서비스에서 검증). Reservation Service: Reservation Token 검증 후 즉시 삭제(REQ-RSV-007), 예매 성공 시 Payment Token 발급하여 응답에 포함. Payment Service: Payment Token 검증 후 즉시 삭제(REQ-PAY-014). 헤더 누락 시: Gateway는 그대로 전달(각 서비스에서 401), 클라이언트 에러 메시지: "Reservation token required" / "Payment token required" | A개발자 | 기능 | 필수 | 이중 대기열 토큰 전달, REQ-QUEUE-016 연계 |
| REQ-GW-022 | Fallback 응답 정의 | Circuit Breaker Open 시 응답. 상태 코드: 503 Service Unavailable. Body: {"error": "Service temporarily unavailable", "serviceName": "xxx", "retryAfter": 30}. 서비스별 fallback 커스터마이징 | A개발자 | 기능 | 필수 | REQ-GW-007 연계 |
| REQ-GW-023 | Retry Policy | 재시도 대상: GET, PUT, DELETE (Idempotent). 재시도 안함: POST. 조건: 502, 503, 504. 최대 2회, Backoff: 100ms, 200ms (Exponential). Circuit Open 시: 재시도 안함 | A개발자 | 비기능 | 선택 | 일시적 장애 재시도 |
| REQ-GW-024 | Payment 엔드포인트 Rate Limiting 강화 | Payment 엔드포인트에 추가 Rate Limiting 적용하여 PG 과부하 방지. 기존(REQ-GW-006): POST /payments 20회/분 per user. 신규 강화: IP 기반 추가 제한 50회/분(REQ-GW-005 확장), Global 제한 1000 TPS(전체 시스템), PG API 보호 Circuit Breaker (REQ-GW-007 활용, Payment Service 전용 CB, waitDuration: 60s 기존 유지, slowCallDurationThreshold: 10s PG timeout 기준). 알고리즘: User-based Redis Token Bucket, Global Redis Counter (rolling window 1초), 초과 시 429 Too Many Requests, Body: {retryAfter: 60, reason: "Payment rate limit exceeded"} | A개발자 | 비기능 | 필수 | REQ-GW-005, 006, 007 연계 |

---
