# 📌 프로젝트 요구사항 명세서

## 문서 개요

이 문서는 **콘서트/공연 티켓팅 시스템**의 MSA 아키텍처 기반 요구사항을 정의합니다.

### 요구사항 통계

| 분류 | 개수 | 비율 |
|------|------|------|
| 기능 요구사항 | 45 | 63.4% |
| 비기능 요구사항 | 26 | 36.6% |
| **합계** | **71** | **100%** |

| 우선순위 | 개수 | 비율 |
|----------|------|------|
| 필수 | 56 | 78.9% |
| 선택 | 15 | 21.1% |

### MSA 구성

- **User Service (B개발자)**: 회원/인증 기능
- **Event Service (A개발자)**: 공연/공연장/홀 관리
- **Reservation Service (B개발자)**: 대기열 및 예매 관리
- **Payment Service (B개발자)**: 결제 처리
- **API Gateway (A개발자)**: Spring Cloud Gateway 기반 진입점

---

## 1. 회원 / 인증 (AUTH)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-AUTH-001 | 회원가입 | 이메일, 비밀번호, 이름, 전화번호를 입력받아 신규 회원 등록 | B개발자 | 기능 | 필수 | POST /auth/signup |
| REQ-AUTH-002 | 로그인 | 이메일, 비밀번호 검증 후 JWT Access/Refresh Token 발급 | B개발자 | 기능 | 필수 | POST /auth/login |
| REQ-AUTH-003 | 로그아웃 | 세션 종료 처리 | B개발자 | 기능 | 필수 | POST /auth/logout |
| REQ-AUTH-004 | 프로필 조회 | 로그인한 사용자의 프로필 정보 조회 | B개발자 | 기능 | 선택 | GET /auth/profile |
| REQ-AUTH-005 | 프로필 수정 | 이름, 전화번호 수정 | B개발자 | 기능 | 선택 | PUT /auth/profile |
| REQ-AUTH-006 | 토큰 갱신 | Refresh Token으로 새 Access Token 발급 | B개발자 | 기능 | 필수 | POST /auth/refresh |
| REQ-AUTH-007 | 비밀번호 암호화 | BCrypt 암호화 적용 | B개발자 | 기능 | 필수 | 보안 필수 |
| REQ-AUTH-008 | 이메일 중복 확인 | 회원가입 시 이메일 중복 검사 | B개발자 | 기능 | 필수 | 409 Conflict |
| REQ-AUTH-009 | 권한 기반 접근 제어 | USER / ADMIN 권한 구분 | B개발자 | 기능 | 필수 | RBAC |
| REQ-AUTH-010 | JWT 토큰 만료 시간 | Access 1시간 / Refresh 7일 | B개발자 | 비기능 | 필수 | 보안 정책 |
| REQ-AUTH-011 | 개인정보 컬럼 암호화 | 이메일, 이름, 전화번호 암호화 저장 | B개발자 | 기능 | 선택 | AES-256 |
| REQ-AUTH-012 | 이메일 인증 | 인증번호 발송 및 검증 | B개발자 | 기능 | 선택 | SMTP |
| REQ-AUTH-013 | 회원 탈퇴 | DB에서 탈퇴 여부 업데이트 | B개발자 | 기능 | 필수 | Soft Delete |
| REQ-AUTH-014 | 비밀번호 변경 | 로그인 상태에서 비밀번호 변경 | B개발자 | 기능 | 선택 | PUT /auth/password |
| REQ-AUTH-015 | 비밀번호 찾기 | 아이디 인증 후 비밀번호 재설정 | B개발자 | 기능 | 선택 | POST /auth/reset-password |

---

## 2. 공연 관리 (EVENT)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-EVT-001 | 공연 생성 (관리자) | 제목, 아티스트, 일시, 공연장, 좌석 정보 등 입력 | A개발자 | 기능 | 선택 | POST /admin/events |
| REQ-EVT-002 | 공연 정보 수정 | 공연 정보 수정 | A개발자 | 기능 | 선택 | PUT /admin/events/{id} |
| REQ-EVT-003 | 공연 삭제 | 공연 정보 삭제 | A개발자 | 기능 | 선택 | DELETE /admin/events/{id} |
| REQ-EVT-004 | 공연 목록 조회 | 페이징, 필터링, 정렬, 검색 지원 | A개발자 | 기능 | 필수 | GET /events |
| REQ-EVT-005 | 공연 상세 조회 | 상세 정보 및 잔여 좌석 수 표시 | A개발자 | 기능 | 필수 | GET /events/{id} |
| REQ-EVT-006 | 공연 좌석 정보 조회 | 등급별 좌석 목록 및 상태 조회 | A개발자 | 기능 | 필수 | GET /events/{id}/seats |
| REQ-EVT-007 | 공연 상태 관리 | UPCOMING / ONGOING / ENDED | A개발자 | 기능 | 선택 | Enum 상태 |
| REQ-EVT-008 | 좌석 상태 관리 | AVAILABLE / HOLD / SOLD | A개발자 | 기능 | 선택 | Enum 상태 |
| REQ-EVT-009 | 좌석 등급 관리 | VIP / S / A / B | A개발자 | 기능 | 선택 | Enum 등급 |
| REQ-EVT-010 | 공연장 생성 (관리자) | 공연장명, 주소 등 등록 | A개발자 | 기능 | 선택 | POST /admin/venues |
| REQ-EVT-011 | 공연장 수정 | 공연장 정보 수정 | A개발자 | 기능 | 선택 | PUT /admin/venues/{id} |
| REQ-EVT-012 | 공연장 삭제 | 공연장 정보 삭제 | A개발자 | 기능 | 선택 | DELETE /admin/venues/{id} |
| REQ-EVT-013 | 홀 정보 생성 | 홀명, 좌석 정보 등록 | A개발자 | 기능 | 선택 | POST /admin/venues/{id}/halls |
| REQ-EVT-014 | 홀 정보 수정 | 홀 정보 수정 | A개발자 | 기능 | 선택 | PUT /admin/halls/{id} |
| REQ-EVT-015 | 홀 정보 삭제 | 홀 정보 삭제 | A개발자 | 기능 | 선택 | DELETE /admin/halls/{id} |

---

## 3. 대기열 (WAITING)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-WAIT-001 | 대기열 진입 | 예매 페이지 접근 시 대기열 진입 | A개발자 | 기능 | 필수 | POST /queue/enter |
| REQ-WAIT-002 | 대기열 상태 조회 | 현재 순서 및 예상 대기 시간 표시 | A개발자 | 기능 | 필수 | GET /queue/status |
| REQ-WAIT-003 | 대기열 만료 처리 | 일정 시간 후 자동 만료 | A개발자 | 기능 | 필수 | Redis TTL |

---

## 4. 예매 (RESERVATION)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-RSV-001 | 예매 생성 | 좌석 선택 후 예매 요청 | B개발자 | 기능 | 필수 | 이벤트 발행 |
| REQ-RSV-002 | 예매 상세 조회 | 예매 상세 정보 조회 | B개발자 | 기능 | 필수 | GET /reservations/{id} |
| REQ-RSV-003 | 예매 취소 | 공연 24시간 전까지 취소 가능 | B개발자 | 기능 | 필수 | POST /reservations/{id}/cancel |
| REQ-RSV-004 | 사용자 예매 내역 조회 | 마이페이지 예매 목록 | B개발자 | 기능 | 필수 | GET /users/{id}/reservations |
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
| REQ-PAY-001 | 결제 생성 | 예매에 대한 결제 요청 | B개발자 | 기능 | 필수 | POST /payments |
| REQ-PAY-002 | 결제 확인 | PG 응답 검증 및 완료 처리 | B개발자 | 기능 | 필수 | POST /payments/{id}/confirm |
| REQ-PAY-003 | 결제 조회 | 결제 상세 정보 조회 | B개발자 | 기능 | 필수 | GET /payments/{id} |
| REQ-PAY-004 | 사용자 결제 내역 조회 | 마이페이지 결제 목록 | B개발자 | 기능 | 필수 | GET /users/{id}/payments |
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

## 6. API GATEWAY (Spring Cloud)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-GW-001 | 동적 라우팅 | Path 기반 Predicate로 4개 마이크로서비스 라우팅: /auth, /users → User Service, /events, /venues, /admin/events, /admin/venues → Event Service, /reservations → Reservation Service, /payments → Payment Service | A개발자 | 기능 | 필수 | RouteLocator |
| REQ-GW-002 | JWT 토큰 검증 필터 | Authorization 헤더의 Bearer Token 검증, 만료/변조 확인 후 userId, role 추출하여 X-User-Id, X-User-Role 헤더로 다운스트림 전달 | A개발자 | 기능 | 필수 | GatewayFilter |
| REQ-GW-003 | 공개 엔드포인트 허용 | 인증 없이 접근 가능한 경로 허용: POST /auth/signup, POST /auth/login, GET /events, GET /events/{id}, GET /venues | A개발자 | 기능 | 필수 | Predicate 조건 |
| REQ-GW-004 | CORS 설정 | Preflight 요청 처리, Vercel 프론트엔드 Origin 허용, 허용 메서드(GET, POST, PUT, DELETE), 허용 헤더(Authorization, Content-Type), 인증정보 포함(credentials: true) | A개발자 | 기능 | 필수 | CorsConfiguration |
| REQ-GW-005 | IP 기반 Rate Limiting | IP당 분당 100회 요청 제한, Redis 기반 Token Bucket 알고리즘, 초과 시 429 Too Many Requests 응답 | A개발자 | 비기능 | 필수 | RequestRateLimiter |
| REQ-GW-006 | 사용자 기반 Rate Limiting | 인증된 사용자 userId당 분당 200회 요청 제한, IP 제한과 독립적으로 적용, 우회 방지 | A개발자 | 비기능 | 필수 | Custom Filter |
| REQ-GW-007 | Circuit Breaker 통합 | Resilience4j Circuit Breaker 적용, 다운스트림 서비스 장애 시 fallback 응답 반환, slidingWindowSize: 20, failureRateThreshold: 50%, waitDuration: 30s | A개발자 | 비기능 | 선택 | CircuitBreakerFilter |
| REQ-GW-008 | 글로벌 타임아웃 설정 | 모든 라우트에 기본 타임아웃 30초 적용, 다운스트림 무응답 시 504 Gateway Timeout 응답 | A개발자 | 비기능 | 필수 | connect/response timeout |
| REQ-GW-009 | 라우트별 타임아웃 커스터마이징 | 결제 API(/payments)는 60초, 대기열 조회(/reservations/queue)는 10초 타임아웃 설정 | A개발자 | 비기능 | 선택 | Route metadata |
| REQ-GW-010 | Request ID 전파 | 모든 요청에 고유 X-Request-ID 생성 또는 클라이언트 전달값 재사용, 다운스트림 서비스 및 로그에 전파하여 분산 추적 지원 | A개발자 | 비기능 | 필수 | AddRequestHeader |
| REQ-GW-011 | 요청/응답 로깅 | 요청 메서드, 경로, 응답 상태 코드, 처리 시간 로깅, JSON 구조화 로그, CloudWatch Logs 전송 | A개발자 | 비기능 | 필수 | GlobalFilter |
| REQ-GW-012 | 보안 헤더 추가 | X-Content-Type-Options: nosniff, X-Frame-Options: DENY, X-XSS-Protection: 1; mode=block, Strict-Transport-Security: max-age=31536000 헤더 추가 | A개발자 | 비기능 | 필수 | AddResponseHeader |
| REQ-GW-013 | 게이트웨이 헬스체크 | /actuator/health 엔드포인트 제공, 다운스트림 서비스 상태 집계(User, Event, Reservation, Payment), ALB 타겟 헬스체크 연동 | A개발자 | 기능 | 필수 | Spring Actuator |
| REQ-GW-014 | CloudWatch 메트릭 통합 | 라우트별 요청 수, 응답 시간, 에러율 메트릭 수집, CloudWatch Metrics 전송, 임계값 초과 시 CloudWatch Alarm 트리거 | A개발자 | 비기능 | 필수 | Micrometer |
| REQ-GW-015 | Response Body 압축 | 1KB 이상 응답 데이터 gzip 압축, Accept-Encoding 헤더 확인, Content-Encoding 헤더 추가로 네트워크 대역폭 절감 | A개발자 | 비기능 | 선택 | Compression filter |

---

## 문서 버전 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|-----------|
| 1.0.0 | 2025-12-26 | 초기 버전 - MSA별 담당자 지정(A개발자/B개발자), Spring  |
