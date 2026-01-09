# 📌 프로젝트 요구사항 명세서

## 문서 개요

이 문서는 **콘서트/공연 티켓팅 시스템**의 MSA 아키텍처 기반 요구사항을 정의합니다.

### 요구사항 통계

| 분류 | 개수 | 비율 |
|------|------|------|
| 기능 요구사항 | 68 | 61.3% |
| 비기능 요구사항 | 43 | 38.7% |
| **합계** | **111** | **100%** |

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
| REQ-AUTH-001 | 회원가입 프로세스 | **약관동의 > CAPTCHA > 본인인증 > 정보입력** 순서의 단계별 가입 절차 구현 | B개발자 | 기능 | 필수 | 트랜잭션 보장 |
| REQ-AUTH-002 | 이용약관 동의 | 서비스 이용약관, 개인정보 수집 동의(필수/선택) 및 동의 일시/버전 저장 | B개발자 | 기능 | 필수 | 약관 버전 관리 |
| REQ-AUTH-003 | 부정 가입/로그인 방지 | 회원가입 및 로그인 시도시 **reCAPTCHA** 검증으로 봇/매크로 차단 | B개발자 | 비기능 | 필수 | Google reCAPTCHA |
| REQ-AUTH-004 | 본인 인증 (CI/DI) | **PortOne** 테스트 모드 연동, 가상 CI/DI 수집을 통한 **1인 1계정** 강제 | B개발자 | 기능 | 필수 | 중복 가입 차단 |
| REQ-AUTH-005 | 이메일 중복 확인 | 가입 시도 이메일의 중복 여부 검사 (본인인증 후 수행 권장) | B개발자 | 기능 | 필수 | 409 Conflict |
| REQ-AUTH-006 | 로그인 | 이메일, 비밀번호, **CAPTCHA** 검증 후 JWT Access/Refresh Token 발급 | B개발자 | 기능 | 필수 | |
| REQ-AUTH-007 | 소셜 로그인 | 카카오, 네이버, 구글 등 OAuth2 간편 로그인 지원 (기존 계정 연동) | B개발자 | 기능 | 선택 | |
| REQ-AUTH-008 | 로그아웃 | Refresh Token 삭제 및 Access Token 블랙리스트 처리 | B개발자 | 기능 | 필수 | |
| REQ-AUTH-009 | 토큰 갱신 | Refresh Token 유효성 검증 후 Access Token 재발급 (RTR 적용 고려) | B개발자 | 기능 | 필수 | |
| REQ-AUTH-010 | 토큰 블랙리스트 | 로그아웃된 Access Token의 남은 유효기간 동안 **Redis** 블랙리스트 등록 | B개발자 | 비기능 | 필수 | JWT 보안 보완 |
| REQ-AUTH-011 | JWT 토큰 정책 | Access(1시간), Refresh(7일) 만료 시간 설정 및 서명 검증 | B개발자 | 비기능 | 필수 | 보안 정책 |
| REQ-AUTH-012 | 프로필 조회 | 로그인한 사용자의 프로필 정보 조회 | B개발자 | 기능 | 선택 | |
| REQ-AUTH-013 | 프로필 수정 | 이름, 전화번호 등 개인정보 수정 | B개발자 | 기능 | 선택 | |
| REQ-AUTH-014 | 비밀번호 암호화 | **BCrypt** 알고리즘을 사용한 비밀번호 단방향 암호화 저장 | B개발자 | 기능 | 필수 | 보안 필수 |
| REQ-AUTH-015 | 비밀번호 찾기 | 본인 인증(CI) 확인 후 비밀번호 재설정 기능 | B개발자 | 기능 | 선택 | |
| REQ-AUTH-016 | 비밀번호 변경 | 로그인 상태에서 기존 비밀번호 확인 후 변경 | B개발자 | 기능 | 선택 | |
| REQ-AUTH-017 | 권한 기반 접근 제어 | USER / ADMIN 권한 분리 및 API 접근 제어 (RBAC) | B개발자 | 기능 | 필수 | Spring Security |
| REQ-AUTH-018 | 회원 탈퇴 | DB 내 상태값 변경(Soft Delete) 및 개인정보 파기 시점 관리 | B개발자 | 기능 | 필수 | 개인정보보호법 |
| REQ-AUTH-019 | 휴면 계정 처리 | 1년 이상 미로그인 계정 분리 보관 또는 자동 탈퇴 처리 | B개발자 | 기능 | 선택 | 배치 작업 |
| REQ-AUTH-020 | 개인정보 컬럼 암호화 | 이메일, 전화번호, CI 등 민감 정보 **AES-256** 암호화 저장 | B개발자 | 기능 | 선택 | DB 보안 |
| REQ-AUTH-021 | 로그인 이력 관리 | 접속 IP, User-Agent, 접속 시간 기록 및 의심 접속 탐지 | B개발자 | 기능 | 선택 | 보안 감사 |
| REQ-AUTH-022 | 이메일 인증 | (선택) 회원가입 시 이메일 소유 확인을 위한 인증번호 발송 | B개발자 | 기능 | 선택 | SMTP |

---

## 2. 공연 관리 (EVENT)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-EVT-001 | 공연 생성 (관리자) | 제목, 아티스트, 일시, 공연장, 좌석 정보 등록 | A개발자 | 기능 | 필수 | |
| REQ-EVT-002 | 공연 정보 수정 | 판매 시작 전후 수정 가능 범위 차등 적용 | A개발자 | 기능 | 선택 | |
| REQ-EVT-003 | 공연 삭제 | Soft Delete 방식, 예매 건 있으면 삭제 불가 | A개발자 | 기능 | 선택 | |
| REQ-EVT-004 | 공연 목록 조회 | 페이징, 필터링, 정렬, 검색 지원 | A개발자 | 기능 | 필수 | P95 < 200ms |
| REQ-EVT-005 | 공연 상세 조회 | 공연 정보 및 잔여 좌석 수 조회 | A개발자 | 기능 | 필수 | P95 < 100ms |
| REQ-EVT-006 | 공연 좌석 정보 조회 | 등급별(VIP/S/A/B) 그룹핑 조회 | A개발자 | 기능 | 필수 | P95 < 300ms |
| REQ-EVT-007 | 공연 상태 관리 | UPCOMING / ONGOING / ENDED / CANCELLED | A개발자 | 기능 | 필수 | |
| REQ-EVT-008 | 좌석 상태 관리 | HOLD: Redis TTL 관리, SOLD: RDB 저장. Kafka 이벤트로 상태 전이 | A개발자 | 기능 | 필수 | REQ-EVT-026 연계 |
| REQ-EVT-009 | 좌석 등급 관리 | VIP / S / A / B 등급 관리 | A개발자 | 기능 | 필수 | |
| REQ-EVT-010 | 공연장 생성 (관리자) | 공연장명, 주소 등록 | A개발자 | 기능 | 필수 | |
| REQ-EVT-011 | 공연장 수정 | 공연장 정보 수정 | A개발자 | 기능 | 선택 | |
| REQ-EVT-012 | 공연장 삭제 | 공연장 정보 삭제 | A개발자 | 기능 | 선택 | |
| REQ-EVT-013 | 홀 정보 생성 | 홀명, 좌석 템플릿 등록 | A개발자 | 기능 | 필수 | |
| REQ-EVT-014 | 홀 정보 수정 | 홀 정보 수정 | A개발자 | 기능 | 선택 | |
| REQ-EVT-015 | 홀 정보 삭제 | 홀 정보 삭제 | A개발자 | 기능 | 선택 | |
| REQ-EVT-016 | Kafka Consumer 구현 | 예매 이벤트 수신하여 좌석 상태 업데이트 | A개발자 | 기능 | 필수 | |
| REQ-EVT-017 | Redis 캐싱 전략 | 조회 성능 향상을 위한 캐싱 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-018 | QueryDSL 동적 쿼리 | 복잡한 필터링/검색 구현 | A개발자 | 기능 | 필수 | |
| REQ-EVT-019 | 좌석 초기화 로직 | 공연 생성 시 좌석 자동 생성 | A개발자 | 기능 | 필수 | |
| REQ-EVT-020 | 캐시 무효화 이벤트 처리 | 상태 변경 시 캐시 삭제 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-021 | 성능 목표 (SLO) | 엔드포인트별 응답 시간 목표 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-022 | DB Read Replica 사용 | 읽기 트래픽 분산 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-023 | 좌석 동시성 제어 | Optimistic Lock 기반 동시성 제어 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-024 | Dead Letter Queue 처리 | 실패 이벤트 보관 및 모니터링 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-025 | 공연 검색 Full-text Search | ElasticSearch 연동 (향후) | A개발자 | 기능 | 선택 | |
| REQ-EVT-026 | 좌석 동기 선점 API | Reservation Service가 동기 호출하여 좌석 선점 | A개발자 | 기능 | 필수 | 좌석 중복 선점 방지 핵심 |
| REQ-EVT-027 | 캐시 Stampede 방지 | 대규모 동시 조회 시 DB 보호 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-028 | 공연 취소 처리 | 공연 취소 시 예매자 보호 | A개발자 | 기능 | 필수 | |
| REQ-EVT-029 | 공연 일정 변경 처리 | 일정 변경 시 알림 및 환불 정책 | A개발자 | 기능 | 선택 | |
| REQ-EVT-030 | 좌석 재고 정합성 검증 | 서비스 간 좌석 상태 불일치 탐지 | A개발자 | 비기능 | 필수 | |
| REQ-EVT-031 | 티켓팅 오픈 시 캐시 정책 | 오픈 직후 캐시 TTL 동적 조정 | A개발자 | 비기능 | 필수 | |

---

## 3. 대기열 (QUEUE)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-QUEUE-001 | 대기열 진입 | 예매하기 버튼 클릭 시 Redis Sorted Set 진입, 중복 시 기존 순서 반환 | A개발자 | 기능 | 필수 | ZADD NX |
| REQ-QUEUE-002 | 대기열 상태 조회 | REST API 폴링(5초 권장)으로 현재 순서, 예상 대기 시간, 토큰 상태 조회 | A개발자 | 기능 | 필수 | 60회/분 제한 |
| REQ-QUEUE-003 | 대기열 만료 처리 | Queue Token TTL 10분, 비활성 사용자 5분 후 제거 | A개발자 | 기능 | 필수 | Redis TTL |
| REQ-QUEUE-004 | Queue Token 발급/검증 | 이중 Token 모델: Reservation Token(qr_xxx), Payment Token(qp_xxx) 1회용 검증 | A개발자 | 기능 | 필수 | REQ-QUEUE-016 연계 |
| REQ-QUEUE-005 | 배치 승인 처리 | 1초마다 10명씩 승인, Lua 스크립트 원자성 보장 | A개발자 | 기능 | 필수 | 36,000명/시간 |
| REQ-QUEUE-010 | 대기열 용량 제한 | 공연별 최대 50,000명, 초과 시 503 응답 | A개발자 | 기능 | 필수 | 과부하 방지 |
| REQ-QUEUE-012 | 대기열 모니터링 API | 관리자용 대기열 통계 조회 API | A개발자 | 기능 | 필수 | ADMIN 권한 |
| REQ-QUEUE-014 | Rate Limiting | GET /queue/status: 60회/분 per user, 초과 시 429 | A개발자 | 비기능 | 필수 | REQ-GW-006 연계 |
| REQ-QUEUE-015 | 대기열 성능 목표 | 진입 P95<100ms, 조회 P95<50ms, Redis 가용성 99.9% | A개발자 | 비기능 | 필수 | SLO |
| REQ-QUEUE-021 | 다중 대기열 제한 | 사용자당 동시 대기 최대 3개 공연, 초과 시 409 | A개발자 | 기능 | 필수 | 어뷰징 방지 |

---

## 4. 예매 (RESERVATION)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-RSV-001 | 좌석 선점 (임시 예매) | Redisson 분산 락으로 좌석 선점, Redis 5분 TTL, RDB 예매정보 생성(PENDING) | B개발자 | 기능 | 필수 | 좌석 선점권 |
| REQ-RSV-002 | 좌석 변경 처리 | 선점 중 좌석 변경 시 기존 락 해제 → 신규 좌석 락 획득 → 예매정보 좌석값 업데이트 | B개발자 | 기능 | 필수 | REQ-RSV-001 연계 |
| REQ-RSV-003 | 좌석 상태 조회 | 공연별 실시간 좌석 상태 조회 (Redis HOLD + RDB SOLD + 나머지 AVAILABLE) | B개발자 | 기능 | 필수 | REQ-EVT-006 연계 |
| REQ-RSV-004 | 예매 확정 (결제 완료) | 결제 서비스의 성공 이벤트를 수신하여 예매 상태를 '확정(CONFIRMED)'으로 변경 | B개발자 | 기능 | 필수 | Kafka Consumer |
| REQ-RSV-005 | 1회 예매 매수 제한 | 좌석 선점(REQ-RSV-001) 시, 이번 거래에서 사용자가 선택한 좌석 개수를 확인하여 1회 최대 4장 제한. 초과 시 409 Conflict 응답 | B개발자 | 기능 | 필수 | REQ-RSV-001 연계 |
| REQ-RSV-006 | 예매 취소 (사용자) | 사용자의 요청에 의한 예매 취소 처리 (공연일 기준 취소 수수료 정책 적용 가능) | B개발자 | 기능 | 필수 | 상태 변경 |
| REQ-RSV-007 | 선점 만료 자동 해제 | 선점 시간(5분) 내 미결제 시 스케줄러/TTL을 통해 좌석 점유 해제 및 상태 변경(EXPIRED) | B개발자 | 기능 | 필수 | Redis Key Expire |
| REQ-RSV-008 | 대기열 토큰 검증 | 좌석 조회/예매 요청 시 Queue Service의 Reservation Token(qr_xxx) 유효 여부 2차 검증 | B개발자 | 기능 | 필수 | 새치기 방지 |
| REQ-RSV-009 | 나의 예매 내역 조회 | 사용자별 예매 목록 조회 (공연명, 일시, 좌석, 상태 등 포함) | B개발자 | 기능 | 필수 | 페이징 |
| REQ-RSV-010 | 예매 상세 조회 | 특정 예매 건의 상세 정보 및 QR코드/티켓 번호 조회 | B개발자 | 기능 | 필수 | |
| REQ-RSV-011 | Kafka 이벤트 발행 | 예매 확정(CONFIRMED), 예매 취소(CANCELLED) 시 Kafka 이벤트 발행. Event Service가 구독하여 좌석 상태 업데이트 (Outbox 패턴 적용) | B개발자 | 기능 | 필수 | 신뢰성 |
| REQ-RSV-012 | Transactional Outbox Pattern | Outbox로 메시지 유실 방지: 상태 변경과 기록을 동일 트랜잭션 처리 후 Poller/CDC로 Kafka 발행 | B개발자 | 비기능 | 필수 | 메시지 신뢰성 |
| REQ-RSV-013 | 대기열 토큰 만료 처리 | 예매 확정(CONFIRMED) 후 Queue Service에 Payment Token(qp_xxx) 만료 요청 | B개발자 | 기능 | 필수 | Feign |

---

## 5. 결제 (PAYMENT)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-PAY-001 | 결제 수단 지원 | PortOne 기반 신용카드(CARD) 결제 | B개발자 | 기능 | 필수 | PortOne |
| REQ-PAY-002 | PortOne 테스트 모드 | PortOne 테스트 모드로 가상 결제 처리 | B개발자 | 기능 | 선택 | 개발/테스트 |
| REQ-PAY-003 | 결제 상태 관리 | PENDING / SUCCESS / FAILED / REFUNDED | B개발자 | 기능 | 필수 | 상태 머신 |
| REQ-PAY-004 | 멱등성 키 | 중복 결제 방지용 멱등성 키(paymentKey) 사용. 이벤트/결제 처리는 멱등성 키 기반 검사로 중복 적용 방지 | B개발자 | 비기능 | 필수 | paymentKey |
| REQ-PAY-005 | 결제 전 상태 검증 | 결제 진행 전 예매 상태(PENDING) 및 좌석 선점 유효성(Redis) 검증 | B개발자 | 기능 | 필수 | 유효성 검사 |
| REQ-PAY-006 | 결제 정보 생성 | 예매 ID, 결제 금액, 결제 수단 등을 포함한 결제 레코드 생성 및 DB 저장 (상태: PENDING) | B개발자 | 기능 | 필수 | |
| REQ-PAY-007 | PortOne 사전 검증 등록 | 생성된 결제 정보를 바탕으로 PortOne 사전 검증(Prepare) API 호출하여 금액 등록 | B개발자 | 기능 | 필수 | PortOne Prepare |
| REQ-PAY-008 | 결제 타임아웃 | PortOne API 호출 10초 초과 시 | B개발자 | 비기능 | 필수 | Timeout |
| REQ-PAY-009 | Circuit Breaker | PortOne 장애 시 연쇄 장애 방지 | B개발자 | 비기능 | 필수 | Resilience4j |
| REQ-PAY-010 | 결제 확인 | PG 응답 검증 및 완료 처리. 결제 성공 시 결제 성공 이벤트 발행하여 Reservation Service가 수신하도록 함(Outbox 권장). 멱등성 키로 재시도 안전 보장 | B개발자 | 기능 | 필수 | |
| REQ-PAY-011 | SAGA 패턴 | 결제 성공 시 이벤트 발행, 실패 시 보상 트랜잭션 실행 | B개발자 | 기능 | 필수 | 오케스트레이션 |
| REQ-PAY-012 | 보상 트랜잭션 | 결제 실패 시 Outbox로 보상 이벤트 발행하여 Reservation이 예매 취소(CANCELLED) 처리 | B개발자 | 기능 | 필수 | Rollback |
| REQ-PAY-013 | Kafka 이벤트 발행 | 결제 성공/실패 이벤트 발행하여 Reservation/Event Service가 수신하도록 함 | B개발자 | 기능 | 필수 | Outbox 권장 |
| REQ-PAY-014 | 결제 조회 | 결제 상세 정보 조회 | B개발자 | 기능 | 필수 | |
| REQ-PAY-015 | 사용자 결제 내역 조회 | 마이페이지 결제 목록 조회 | B개발자 | 기능 | 필수 | |

---

## 6. API GATEWAY (SPRING CLOUD)

| 요구사항 ID | 요구사항명 | 요구사항 설명 | 담당자 | 분류 | 필수여부 | 비고 |
|------------|-----------|-------------|-------|------|---------|-----|
| REQ-GW-001 | 동적 라우팅 | Path 기반으로 5개 마이크로서비스로 요청 라우팅 (/auth, /users → User / /events, /venues → Event / /queue → Queue / /reservations → Reservation / /payments → Payment) | A개발자 | 기능 | 필수 | |
| REQ-GW-002 | JWT 토큰 검증 필터 | JWT 토큰 검증 (만료/변조 확인) 후 사용자 정보를 다운스트림 서비스로 전달 | A개발자 | 기능 | 필수 | |
| REQ-GW-003 | 공개 엔드포인트 허용 | 인증 없이 접근 가능한 경로 허용 (회원가입, 로그인, 공연/공연장 조회) | A개발자 | 기능 | 필수 | |
| REQ-GW-004 | CORS 설정 | 프론트엔드 Cross-Origin 요청 허용 (Vercel Origin, 허용 메서드/헤더 설정, 인증정보 포함) | A개발자 | 기능 | 필수 | |
| REQ-GW-005 | IP 기반 Rate Limiting | 엔드포인트별 IP 기반 Rate Limiting 적용 (읽기: 300회/분, 기타: 100회/분) | A개발자 | 비기능 | 필수 | |
| REQ-GW-006 | 사용자 기반 Rate Limiting | 엔드포인트별 사용자 기반 Rate Limiting 적용 (대기열 조회: 60회/분, 예매/결제: 20회/분, 로그인: 10회/분, 기타: 200회/분) | A개발자 | 비기능 | 필수 | |
| REQ-GW-007 | Circuit Breaker 통합 | 다운스트림 서비스 장애 시 Circuit Breaker로 연쇄 장애 방지 (실패율 임계값 초과 시 Circuit Open, Payment 서비스: 대기시간 60초) | A개발자 | 비기능 | 선택 | |
| REQ-GW-008 | 글로벌 타임아웃 설정 | 모든 라우트에 기본 타임아웃 30초 적용 (무응답 시 504 Gateway Timeout) | A개발자 | 비기능 | 필수 | |
| REQ-GW-009 | 라우트별 타임아웃 커스터마이징 | 특정 라우트에 타임아웃 커스터마이징 (Payment: 60초, Queue: 10초, 기타: 30초) | A개발자 | 비기능 | 선택 | |
| REQ-GW-010 | Request ID 전파 | 모든 요청에 고유 Request ID 생성/전달하여 분산 추적 지원 | A개발자 | 비기능 | 필수 | |
| REQ-GW-011 | 요청/응답 로깅 | 요청 메서드, 경로, 상태 코드, 처리 시간을 JSON 구조화 로그로 CloudWatch에 전송 | A개발자 | 비기능 | 필수 | |
| REQ-GW-012 | 보안 헤더 추가 | 모든 응답에 보안 헤더 추가 (XSS 방지, Clickjacking 방지, HSTS) | A개발자 | 비기능 | 필수 | |
| REQ-GW-013 | 게이트웨이 헬스체크 | Gateway와 다운스트림 서비스 상태를 집계하여 ALB 헬스체크에 활용 | A개발자 | 기능 | 필수 | |
| REQ-GW-014 | CloudWatch 메트릭 통합 | 라우트별 요청 수, 응답 시간, 에러율 메트릭을 CloudWatch로 전송 및 알람 설정 | A개발자 | 비기능 | 필수 | |
| REQ-GW-015 | Response Body 압축 | 1KB 이상 응답을 gzip 압축하여 네트워크 대역폭 절감 | A개발자 | 비기능 | 선택 | |
| REQ-GW-016 | 로드 밸런싱 전략 | ALB 기반 Round-Robin으로 서비스 인스턴스 간 트래픽 분산 (헬스체크 30초 간격, Unhealthy 인스턴스 자동 제외) | A개발자 | 비기능 | 필수 | |
| REQ-GW-017 | Service Discovery 설정 | ALB DNS 고정 URL 방식으로 서비스 디스커버리 구현 (환경 변수로 서비스 URL 주입, ECS Auto Scaling 연동) | A개발자 | 비기능 | 필수 | |
| REQ-GW-018 | 요청 크기 제한 | 요청 Body 크기 제한으로 대용량 페이로드 공격 방지 (일반: 1MB, 인증: 10KB, 헤더: 16KB) | A개발자 | 비기능 | 필수 | |
| REQ-GW-019 | X-Ray 분산 추적 통합 | AWS X-Ray로 요청 추적 및 성능 병목 지점 식별 (샘플링 10%, Request ID와 별도 관리) | A개발자 | 비기능 | 필수 | |
| REQ-GW-020 | Admin 엔드포인트 권한 검증 | 관리자 전용 엔드포인트 접근 제어 (JWT role 검증, ADMIN 아닌 경우 403 Forbidden) | A개발자 | 기능 | 필수 | |
| REQ-GW-021 | Queue Token 헤더 전달 (이중 Token) | 대기열 토큰 헤더를 각 서비스로 전달 (Reservation Token → Reservation, Payment Token → Payment). 검증은 각 서비스에서 수행 | A개발자 | 기능 | 필수 | REQ-QUEUE-016 연계 |
| REQ-GW-022 | Fallback 응답 정의 | Circuit Breaker Open 시 Fallback 응답 정의 (503 응답, 서비스별 커스터마이징, Payment는 이중 결제 방지 안내 포함) | A개발자 | 기능 | 필수 | REQ-GW-007 연계 |
| REQ-GW-023 | Retry Policy | 일시적 장애 시 자동 재시도 (GET/PUT/DELETE만 재시도, 최대 2회, Exponential Backoff) | A개발자 | 비기능 | 선택 | |
| REQ-GW-024 | Payment 엔드포인트 Rate Limiting 강화 | Payment 엔드포인트에 추가 Rate Limiting 적용 (User: 20회/분, IP: 50회/분, Global: 1000 TPS) 하여 PG 과부하 방지 | A개발자 | 비기능 | 필수 | REQ-GW-005,006,007 확장 |
| REQ-GW-025 | JWT 검증 실패 응답 세분화 | JWT 검증 실패 시 원인별 응답 구분 (만료/변조/누락별 에러 코드)으로 클라이언트 자동 복구 지원 | A개발자 | 기능 | 필수 | |
| REQ-GW-026 | Graceful Shutdown | 배포/스케일링 시 진행 중 요청 보호 (신규 요청 거부, 진행 중 요청 완료 대기, ALB Deregistration 연동) | A개발자 | 비기능 | 필수 | |
| REQ-GW-027 | 봇/어뷰징 탐지 강화 | 다층 방어로 봇/어뷰징 트래픽 탐지 및 차단 (Device Fingerprint, 클라우드 IP 강화 제한, 다중 계정 추적, 패턴 분석) | A개발자 | 비기능 | 필수 | |
| REQ-GW-028 | Rate Limiting 버스트 허용 | 분산 환경에서 실용적 Rate Limiting 구현 (Local + Redis 하이브리드, 버스트 20% 허용, 정확도 95% vs 성능 트레이드오프) | A개발자 | 비기능 | 선택 | |

---
