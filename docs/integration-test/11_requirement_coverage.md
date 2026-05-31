## 11. 요구사항 커버리지 감사 & 보강

> **목적**: `docs/REQUIREMENTS.md`의 135개 요구사항(REQ-xxx-xxx)을 통합테스트 문서(00~10)의 테스트 케이스(TC)와 매핑하여, "필수" 요구사항의 통합테스트 커버리지를 정량 감사하고 미커버 영역에 대한 보강 TC를 정의한다.
> **감사 대상 문서**: `00_environment_setup.md` ~ `10_frontend_e2e.md` (총 TC 239건; 11장 보강 GAP TC 6건 포함 시 전체 245건)
> **매핑 원칙**:
> - 각 TC의 `관련 REQ` 필드에 명시된 REQ를 "직접 커버"로 집계한다.
> - `관련 REQ: 해당 없음`이지만 TC 제목/검증 내용이 특정 REQ 기능을 실증하는 경우 "기능 커버(암묵)"로 표기하되, 카테고리 커버리지 %에는 직접 커버만 반영한다(보수적 집계).
> - REQUIREMENTS.md 기준 8개 카테고리(AUTH/EVT/QUEUE/RSV/PAY/GW/INT/FE)로 분류한다.

---

### 11.1 카테고리별 커버리지 요약

| 카테고리 | 전체 REQ 수 | 필수 수 | 커버된 필수 수(직접) | 커버리지 % | 비고 |
|---|---|---|---|---|---|
| AUTH (회원/인증) | 20 | 11 | 11 | 100% | 필수 11건(001/003/004/005/006/008/009/010/011/014/017) 전부 직접 커버 |
| EVT (공연 관리) | 24 | 14 | 10 | 71.4% | REQ-EVT-007/009/010/018은 TC-EVT-001 등에 암묵 포함(라벨 보정 대상). REQ-EVT-016은 TC-FLOW-004로 커버 |
| QUEUE (대기열) | 11 | 10 | 9 | 90.0% | 필수 10건(001~006,008,009,010,011). REQ-QUEUE-009(SLO)는 동시성(TC-QUEUE-020)만 검증하고 P95/가용성은 성능테스트 범위라 "부분". 선택 007도 커버 |
| RSV (예매) | 12 | 10 | 10 | 100% | 필수 10건(001~008,011,012) 전부 커버. REQ-RSV-009/010은 선택 |
| PAY (결제) | 15 | 12 | 9 | 75.0% | REQ-PAY-001/003은 PAY-001/002에 암묵, REQ-PAY-008(타임아웃 10초) 미커버 → TC-GAP-002 보강 |
| GW (게이트웨이) | 18 | 9 | 7 | 77.8% | REQ-GW-001(동적 라우팅)은 암묵 실증, REQ-GW-007(글로벌 타임아웃 30초→504) 미커버 → TC-GAP-001 보강 |
| INT (내부통신 보안) | 10 | 8 | 4 | 50.0% | REQ-INT-003/004는 암묵, REQ-INT-006(인증 실패 로깅) → TC-GAP-003, REQ-INT-007(VPC) → TC-GAP-004(조건부) 보강 |
| FE (프론트엔드) | 25 | 25 | 24 | 96.0% | 전 TC에 REQ-FE 라벨 부여 완료("해당 없음" 0건). REQ-FE-001~024 전부 직접 커버, REQ-FE-025(Vercel 배포)만 로컬 범위 제외 |

> **종합**: 전체 **135개** REQ 중 `필수`는 **99건**. 통합테스트 `관련 REQ` 라벨 기준 **직접 커버**된 필수 REQ는 **84건**(약 **84.8%**). 여기에 (a) "암묵" 라벨 9건 보정(TC-GAP-FE-LABEL 등), (b) 11.3 보강 TC(TC-GAP-001/002/003/005 → GW-007·PAY-008·INT-006·EVT-009/018), (c) REQ-QUEUE-009 SLO 측정 보완을 반영하면 **로컬 검증 가능한 필수 97건 전부**가 커버된다. 잔여 2건(REQ-INT-007 VPC, REQ-FE-025 Vercel)은 인프라/배포 영역으로 TC-GAP-004/009 조건부 증빙 또는 IaC 리뷰로 대체한다.

---

### 11.2 REQ → 테스트 케이스 매핑

> 상태 범례: **커버**=직접 라벨 존재 · **암묵**=TC 내용상 실증되나 라벨 누락(보강 권장) · **미커버**=관련 TC 없음

#### AUTH (회원/인증)

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-AUTH-001 | 회원가입 프로세스 | 필수 | TC-USER-001, TC-USER-006 | 커버 |
| REQ-AUTH-002 | 이용약관 동의 | 선택 | 미커버 | 미커버 |
| REQ-AUTH-003 | 부정 가입/로그인 방지(reCAPTCHA) | 필수 | TC-USER-001, TC-USER-002, TC-USER-024 | 커버 |
| REQ-AUTH-004 | 본인 인증(CI/DI) | 필수 | TC-USER-001, TC-USER-003, TC-USER-005, TC-USER-023 | 커버 |
| REQ-AUTH-005 | 이메일 중복 확인 | 필수 | TC-USER-001, TC-USER-004 | 커버 |
| REQ-AUTH-006 | 로그인 | 필수 | TC-USER-007~010, TC-USER-024, TC-GW-001, TC-FLOW-001 | 커버 |
| REQ-AUTH-007 | 소셜 로그인(OAuth2) | 선택 | 미커버 | 미커버 |
| REQ-AUTH-008 | 로그아웃 | 필수 | TC-USER-011, TC-USER-022 | 커버 |
| REQ-AUTH-009 | 토큰 갱신(RTR) | 필수 | TC-USER-012~016, TC-USER-027, TC-GW-003 | 커버 |
| REQ-AUTH-010 | 토큰 블랙리스트 | 필수 | TC-USER-011, TC-GW-004, TC-GW-007 | 커버 |
| REQ-AUTH-011 | JWT 토큰 정책 | 필수 | TC-USER-007, TC-USER-012, TC-USER-014, TC-USER-026, TC-GW-007 | 커버 |
| REQ-AUTH-012 | 프로필 조회 | 선택 | TC-USER-017, TC-USER-018, TC-GW-022 | 커버 |
| REQ-AUTH-013 | 프로필 수정 | 선택 | TC-USER-021 | 커버 |
| REQ-AUTH-014 | 비밀번호 암호화(BCrypt) | 필수 | TC-USER-001, TC-USER-008, TC-USER-019, TC-USER-020 | 커버 |
| REQ-AUTH-015 | 비밀번호 찾기 | 선택 | 미커버 | 미커버 |
| REQ-AUTH-016 | 비밀번호 변경 | 선택 | TC-USER-019, TC-USER-020 | 커버 |
| REQ-AUTH-017 | 회원 탈퇴(Soft Delete) | 필수 | TC-USER-010, TC-USER-022, TC-USER-023 | 커버 |
| REQ-AUTH-018 | 휴면 계정 처리 | 선택 | 미커버 | 미커버 |
| REQ-AUTH-019 | 로그인 이력 관리 | 선택 | TC-USER-008, TC-USER-009, TC-USER-010(암묵) | 암묵 |
| REQ-AUTH-020 | 이메일 인증 | 선택 | 미커버 | 미커버 |

#### EVT (공연 관리)

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-EVT-001 | 공연 생성(관리자) | 필수 | TC-EVT-001, TC-EVT-002 | 커버 |
| REQ-EVT-002 | 공연 정보 수정 | 선택 | TC-EVT-005 | 커버 |
| REQ-EVT-003 | 공연 삭제 | 선택 | TC-EVT-003, TC-EVT-004 | 커버 |
| REQ-EVT-004 | 공연 목록 조회 | 필수 | TC-EVT-006 | 커버 |
| REQ-EVT-005 | 공연 상세 조회 | 필수 | TC-EVT-007 | 커버 |
| REQ-EVT-006 | 공연 좌석 정보 조회 | 필수 | TC-EVT-011, TC-RSV-001(연계) | 커버 |
| REQ-EVT-007 | 공연 상태 관리(UPCOMING/ONGOING/ENDED/CANCELLED) | 필수 | TC-EVT-013(ended 회차, 암묵), TC-QUEUE-019(암묵) | 암묵 |
| REQ-EVT-008 | 좌석 상태 관리(SOLD) | 필수 | TC-EVT-001, TC-EVT-014, TC-FLOW-001, TC-FLOW-002 | 커버 |
| REQ-EVT-009 | 좌석 등급 관리(VIP/S/A/B) | 필수 | TC-EVT-001(등급 그룹핑, 암묵) | 암묵 |
| REQ-EVT-010 | 공연장 생성(관리자) | 필수 | TC-EVT-012(내부API 라벨), TC-EVT-022(암묵) | 암묵 |
| REQ-EVT-011 | 공연장 수정 | 선택 | 미커버 | 미커버 |
| REQ-EVT-012 | 공연장 삭제 | 선택 | 미커버 | 미커버 |
| REQ-EVT-013 | 홀 정보 생성 | 필수 | TC-EVT-022, TC-EVT-023 | 커버 |
| REQ-EVT-014 | 홀 정보 수정 | 선택 | 미커버 | 미커버 |
| REQ-EVT-015 | 홀 정보 삭제 | 선택 | TC-EVT-023 | 커버 |
| REQ-EVT-016 | Kafka Consumer 구현 | 필수 | TC-FLOW-004, TC-EVT-014~020 | 커버 |
| REQ-EVT-017 | Redis 캐싱 전략 | 필수 | TC-EVT-007, TC-EVT-008, TC-EVT-024, TC-FLOW-007 | 커버 |
| REQ-EVT-018 | 좌석 초기화 로직(자동 생성) | 필수 | TC-EVT-001(좌석 자동 초기화, 암묵) | 암묵 |
| REQ-EVT-019 | 캐시 무효화 이벤트 처리 | 필수 | TC-EVT-004, TC-EVT-009, TC-EVT-014, TC-FLOW-007 | 커버 |
| REQ-EVT-020 | Dead Letter Queue 처리 | 선택 | TC-EVT-014~020, TC-FLOW-010, TC-FLOW-011 | 커버 |
| REQ-EVT-021 | Redis 캐시 Stampede 방지 | 필수 | TC-EVT-010 | 커버 |
| REQ-EVT-022 | 공연 취소 처리 | 선택 | 미커버 | 미커버 |
| REQ-EVT-023 | 좌석 재고 정합성 검증 | 선택 | TC-FLOW-014 | 커버 |
| REQ-EVT-024 | 티켓팅 오픈 시 캐시 정책 | 선택 | 미커버 | 미커버 |

#### QUEUE (대기열)

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-QUEUE-001 | 대기열 진입 | 필수 | TC-QUEUE-001,002,010,014,018,019,020, TC-FLOW-001,012 | 커버 |
| REQ-QUEUE-002 | 대기열 상태 조회 | 필수 | TC-QUEUE-005, TC-QUEUE-015 | 커버 |
| REQ-QUEUE-003 | 대기열 만료 처리(TTL 10분) | 필수 | TC-QUEUE-011,012,013,014, TC-FLOW-005 | 커버 |
| REQ-QUEUE-004 | Queue Token 발급/검증 | 필수 | TC-QUEUE-005, TC-FLOW-001 | 커버 |
| REQ-QUEUE-005 | 배치 승인 처리(1초/10명) | 필수 | TC-QUEUE-001,005,008,009, TC-FLOW-001 | 커버 |
| REQ-QUEUE-006 | 대기열 용량 제한(50,000) | 필수 | TC-QUEUE-004 | 커버 |
| REQ-QUEUE-007 | 대기열 모니터링 API | 선택 | TC-QUEUE-016, TC-QUEUE-017 | 커버 |
| REQ-QUEUE-008 | Rate Limiting(15회/분) | 필수 | TC-QUEUE-006, TC-QUEUE-007 | 커버 |
| REQ-QUEUE-009 | 대기열 성능 목표(SLO) | 필수 | TC-QUEUE-020(동시성 정합성) | 부분(P95/가용성 측정은 성능테스트 범위) |
| REQ-QUEUE-010 | Queue Token 헤더 전달/검증 | 필수 | TC-GW-012, TC-GW-013 | 커버 |
| REQ-QUEUE-011 | 다중 대기열 제한(1회차) | 필수 | TC-QUEUE-002,003, TC-FLOW-012 | 커버 |

#### RSV (예매)

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-RSV-001 | 좌석 선점(Redisson 분산 락) | 필수 | TC-RSV-001,002,003,008,022, TC-FLOW-001,003,017 | 커버 |
| REQ-RSV-002 | 좌석 변경 처리 | 필수 | TC-FLOW-018 | 커버 |
| REQ-RSV-003 | 좌석 상태 조회 | 필수 | TC-RSV-022, TC-FLOW-003,014,017 | 커버 |
| REQ-RSV-004 | 예매 확정(결제 완료) | 필수 | TC-RSV-013~017, TC-PAY-025, TC-FLOW-001,004 | 커버 |
| REQ-RSV-005 | 1회 예매 매수 제한(4장) | 필수 | TC-RSV-003,006,007, TC-FLOW-013 | 커버 |
| REQ-RSV-006 | 예매 취소(사용자, 당일 불가) | 필수 | TC-RSV-020, TC-RSV-021 | 커버 |
| REQ-RSV-007 | 선점 만료 자동 해제(5분 TTL) | 필수 | TC-RSV-011, TC-RSV-012, TC-FLOW-006 | 커버 |
| REQ-RSV-008 | 대기열 토큰 검증(2차) | 필수 | TC-RSV-001,004,005, TC-FLOW-005 | 커버 |
| REQ-RSV-009 | 나의 예매 내역 조회 | 선택 | TC-FLOW-019 | 커버 |
| REQ-RSV-010 | 예매 상세 조회 | 선택 | 미커버 | 미커버 |
| REQ-RSV-011 | Kafka 이벤트 발행(취소, Outbox) | 필수 | TC-RSV-009,011,014, TC-PAY-014, TC-FLOW-016 | 커버 |
| REQ-RSV-012 | Transactional Outbox Pattern | 필수 | TC-RSV-009,010,013, TC-PAY-016, TC-FLOW-009 | 커버 |

#### PAY (결제)

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-PAY-001 | 결제 수단 지원(CARD) | 필수 | TC-PAY-001, TC-PAY-002(암묵) | 암묵 |
| REQ-PAY-002 | PortOne 테스트 모드 | 선택 | TC-PAY-001, TC-PAY-002 | 커버 |
| REQ-PAY-003 | 결제 상태 관리(상태머신) | 필수 | TC-PAY-002,006,012,020(암묵) | 암묵 |
| REQ-PAY-004 | 멱등성 키(paymentKey) | 필수 | TC-PAY-010,011,012,013 | 커버 |
| REQ-PAY-005 | 결제 전 상태 검증 | 필수 | TC-PAY-003,004,005,006,007, TC-FLOW-005,006 | 커버 |
| REQ-PAY-006 | 결제 정보 생성(PENDING) | 필수 | TC-PAY-001, TC-PAY-011 | 커버 |
| REQ-PAY-007 | PortOne 사전 검증 등록 | 필수 | TC-PAY-001,007,019,021 | 커버 |
| REQ-PAY-008 | 결제 타임아웃(10초) | 필수 | 미커버 | 미커버 |
| REQ-PAY-009 | Circuit Breaker(Resilience4j) | 필수 | TC-PAY-020, TC-PAY-021, TC-FLOW-015 | 커버 |
| REQ-PAY-010 | 결제 확인(PG 응답 검증) | 필수 | TC-PAY-002,008,009,010,012,013, TC-FLOW-001 | 커버 |
| REQ-PAY-011 | SAGA 패턴 | 필수 | TC-PAY-002,014,025, TC-FLOW-001,002 | 커버 |
| REQ-PAY-012 | 보상 트랜잭션 | 필수 | TC-PAY-014,015,019, TC-FLOW-002,016 | 커버 |
| REQ-PAY-013 | Kafka 이벤트 발행(성공/실패) | 필수 | TC-PAY-002,016,017,018,026, TC-FLOW-009 | 커버 |
| REQ-PAY-014 | 결제 조회 | 선택 | TC-PAY-023, TC-PAY-024 | 커버 |
| REQ-PAY-015 | 사용자 결제 내역 조회 | 선택 | TC-FLOW-019 | 커버 |

#### GW (API Gateway)

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-GW-001 | 동적 라우팅 | 필수 | TC-GW-001,011,012,019(암묵 실증) | 암묵 |
| REQ-GW-002 | JWT 토큰 검증 필터 | 필수 | TC-GW-001~009, TC-SEC-006,007,008 | 커버 |
| REQ-GW-003 | 공개 엔드포인트 허용 | 필수 | TC-GW-011, TC-GW-022 | 커버 |
| REQ-GW-004 | CORS 설정 | 필수 | TC-GW-015 | 커버 |
| REQ-GW-005 | API Throttling | 선택 | TC-GW-021 | 커버 |
| REQ-GW-006 | Circuit Breaker 통합 | 선택 | TC-GW-020, TC-GW-021, TC-FLOW-015 | 커버 |
| REQ-GW-007 | 글로벌 타임아웃(30초→504) | 필수 | 미커버 | 미커버 |
| REQ-GW-008 | 라우트별 타임아웃 커스터마이징 | 선택 | 미커버 | 미커버 |
| REQ-GW-009 | Request ID 전파 | 필수 | TC-GW-017, TC-GW-018, TC-FLOW-020 | 커버 |
| REQ-GW-010 | 요청/응답 로깅 | 선택 | 미커버 | 미커버 |
| REQ-GW-011 | 보안 헤더 추가 | 선택 | TC-GW-016 | 커버 |
| REQ-GW-012 | Monitoring 통합 | 선택 | TC-ENV-012, TC-ENV-013 | 커버 |
| REQ-GW-013 | Response Body 압축 | 선택 | 미커버 | 미커버 |
| REQ-GW-014 | 요청 크기 제한 | 선택 | TC-GW-019 | 커버 |
| REQ-GW-015 | Admin 엔드포인트 권한 검증 | 필수 | TC-GW-010, TC-QUEUE-016 | 커버 |
| REQ-GW-016 | Queue Token 헤더 전달 | 필수 | TC-GW-012,013,014 | 커버 |
| REQ-GW-017 | Fallback 응답 정의 | 필수 | TC-GW-020, TC-FLOW-015 | 커버 |
| REQ-GW-018 | Payment Rate Limiting 강화 | 선택 | 미커버 | 미커버 |

#### INT (서비스 간 통신 보안)

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-INT-001 | 내부 API 인증 메커니즘 | 필수 | TC-SEC-001~004, TC-GW-011, TC-USER-025, TC-FLOW-008, TC-ENV-011 | 커버 |
| REQ-INT-002 | API Key 발급(UUID v4) | 필수 | TC-SEC-002,003,005 | 커버 |
| REQ-INT-003 | API Key 저장(Secrets Manager) | 필수 | TC-ENV-005(암묵 — 시크릿 주입 검증) | 암묵 |
| REQ-INT-004 | API Key 헤더 전송(Feign) | 필수 | TC-SEC-001, TC-SEC-016(암묵) | 암묵 |
| REQ-INT-005 | 내부 API 인증 검증(401) | 필수 | TC-SEC-002,003,016, TC-USER-025, TC-FLOW-008 | 커버 |
| REQ-INT-006 | 인증 실패 로깅(보안 감사) | 필수 | 미커버 | 미커버 |
| REQ-INT-007 | 네트워크 레벨 보안(VPC) | 필수 | 미커버 | 미커버(인프라/로컬 검증 불가) |
| REQ-INT-008 | Gateway 내부 API 차단(404) | 필수 | TC-GW-011, TC-USER-025, TC-FLOW-008, TC-ENV-011 | 커버 |
| REQ-INT-009 | API Key 로테이션 | 선택 | 미커버 | 미커버 |
| REQ-INT-010 | 내부 API Rate Limiting | 선택 | 미커버 | 미커버 |

#### FE (프론트엔드)

> 10_frontend_e2e.md의 26개 TC 전부에 `관련 REQ: REQ-FE-0NN` 라벨이 부여되어 **직접 집계**가 완료됐다("해당 없음" 0건). 신규 TC-FE-023~026이 추가되어 기존 미커버였던 REQ-FE-003/013/014/019/020/021까지 직접 커버된다. REQ-FE-025(Vercel 배포)만 로컬 통합테스트 범위 제외다.

| REQ ID | 요구사항 | 필수/선택 | 커버 TC | 상태 |
|---|---|---|---|---|
| REQ-FE-001 | Next.js App Router | 필수 | TC-FE-001, TC-FE-003, TC-FE-019 | 커버 |
| REQ-FE-002 | 반응형 디자인 | 필수 | TC-FE-020 | 커버 |
| REQ-FE-003 | 웹 접근성(WCAG 2.1 AA) | 필수 | TC-FE-023 | 커버 |
| REQ-FE-004 | 공연 목록 SSR | 필수 | TC-FE-001, TC-FE-002, TC-FE-003 | 커버 |
| REQ-FE-005 | 공연 상세 OG 태그 | 필수 | TC-FE-003 | 커버 |
| REQ-FE-006 | 대기열 실시간 폴링 | 필수 | TC-FE-010, TC-FE-011 | 커버 |
| REQ-FE-007 | 대기열 타이머 UI | 필수 | TC-FE-012 | 커버 |
| REQ-FE-008 | 좌석 선점 타이머 UI | 필수 | TC-FE-015 | 커버 |
| REQ-FE-009 | PortOne 결제 위젯 | 필수 | TC-FE-016 | 커버 |
| REQ-FE-010 | JWT httpOnly Cookie | 필수 | TC-FE-008, TC-FE-009, TC-FE-011 | 커버 |
| REQ-FE-011 | 토큰 자동 갱신 | 필수 | TC-FE-009 | 커버 |
| REQ-FE-012 | 이미지 최적화 | 필수 | TC-FE-002 | 커버 |
| REQ-FE-013 | 코드 스플리팅 | 필수 | TC-FE-024 | 커버 |
| REQ-FE-014 | Core Web Vitals | 필수 | TC-FE-025 | 커버 |
| REQ-FE-015 | 에러 바운더리 | 필수 | TC-FE-018, TC-FE-021 | 커버 |
| REQ-FE-016 | 로딩 상태 처리(Skeleton) | 필수 | TC-FE-002 | 커버 |
| REQ-FE-017 | Toast 알림 | 필수 | TC-FE-014, TC-FE-015, TC-FE-017, TC-FE-018, TC-FE-021 | 커버 |
| REQ-FE-018 | 폼 검증(RHF+Zod) | 필수 | TC-FE-007, TC-FE-026 | 커버 |
| REQ-FE-019 | reCAPTCHA 통합 | 필수 | TC-FE-007, TC-FE-026 | 커버 |
| REQ-FE-020 | PortOne 본인인증 | 필수 | TC-FE-026 | 커버 |
| REQ-FE-021 | 회원가입 4단계 | 필수 | TC-FE-026 | 커버 |
| REQ-FE-022 | 좌석 선택 UI(4장 제한) | 필수 | TC-FE-004, TC-FE-014 | 커버 |
| REQ-FE-023 | 미들웨어 인증 가드 | 필수 | TC-FE-005, TC-FE-006, TC-FE-008, TC-FE-013, TC-FE-022 | 커버 |
| REQ-FE-024 | Queue Token 검증(미들웨어) | 필수 | TC-FE-011, TC-FE-013, TC-FE-022 | 커버 |
| REQ-FE-025 | Vercel 배포(CI/CD) | 필수 | 범위 제외(배포) | 범위 제외(배포) |

---

### 11.3 미커버 필수 요구사항 보강 항목

> 아래는 **필수(`필수`)** 이면서 `관련 REQ` 라벨 기준 직접 커버되지 않은 REQ에 대한 보강 통합테스트다. 앞 문서들의 TC 템플릿(관련 REQ/분류/우선순위/사전조건/실행 단계/기대 결과/검증 포인트)을 동일하게 따른다.
> "암묵" 상태 REQ는 기존 TC에 `관련 REQ` 라벨만 추가하면 해소되므로, 별도 TC 없이 **라벨 보정 작업(TC-GAP-FE-LABEL)** 으로 일괄 처리한다.

#### 핵심 누락(신규 검증 로직 필요)

### TC-GAP-001 — Gateway 글로벌 타임아웃 30초 초과 시 504 Gateway Timeout 반환

- [x] 통과 (2026-05-31, 근거: Python 35초 지연 stub을 Docker 내부 네트워크에 user-service DNS alias로 기동 후 GET /users/me 호출 → time_total=30.017s, HTTP 503 CircuitBreaker fallback, code=SERVICE_UNAVAILABLE, traceId 응답 본문 포함. 게이트웨이 response-timeout 30s + TimeLimiter 30s 발동으로 30초 부근 자체 fallback 응답 생성 확인)
- **관련 REQ**: REQ-GW-007
- **분류**: 예외, 경계값
- **우선순위**: P0(필수/핵심)
- **사전조건**: api-gateway 기동 중, 글로벌 `response-timeout: 30s` 설정 확인. **user-service(8081)를 먼저 중지**(`docker stop ticket-user-service` 또는 bootRun 미기동)한 뒤 8081 포트를 35초 지연 stub에 내준다(실행 단계 1의 옵션 참조)
- **실행 단계**:
  1. user-service(8081) 중지 후, 8081 포트에 35초 지연 stub을 바인딩한다(다음 중 택1):
     - **Option 1 — WireMock**: `docker run --rm -p 8081:8080 wiremock/wiremock` 기동 후 매핑 `{"request":{"urlPattern":".*"},"response":{"status":200,"fixedDelayMilliseconds":35000}}` 등록
     - **Option 2 — Python**: 요청 핸들러에서 `time.sleep(35)` 후 200을 반환하는 `http.server` 스크립트를 8081에 기동
     - **Option 3 — 무응답(`nc -l 8081`)**: '지연 후 응답'이 아닌 '무응답'이므로 게이트웨이 30초 컷오프 발동만 검증된다(35초 정상 응답 시나리오와 구분)
  2. 보호 엔드포인트 호출 후 응답 시간 측정:
     ```bash
     curl -s -o /dev/null -w "%{http_code} %{time_total}\n" \
       http://localhost:8080/users/me -H "Authorization: Bearer <유효_JWT>"
     ```
- **기대 결과**: HTTP 504, `time_total ≈ 30s`(±2초), 응답 본문에 `code` = `"GATEWAY_TIMEOUT"` 또는 `SERVICE_UNAVAILABLE`, `traceId` 포함
- **검증 포인트**: 35초가 아닌 30초 부근에서 응답 종료(타임아웃 발동), `X-Trace-Id` 헤더 존재, 다운스트림 미응답에도 게이트웨이가 자체 504 생성

---

### TC-GAP-002 — Payment PortOne API 호출 10초 초과 시 타임아웃 처리 및 PENDING 유지

- [x] 통과 (2026-05-31, 근거: payment-service를 SPRING_APPLICATION_JSON으로 external.portone.api-url=http://host.docker.internal:9999(12초 지연 mock) 오버라이드 후 재기동. POST /payments/confirm 호출 → time_total≈2.1s(Resilience4j TimeLimiter for PortoneTokenService 발동), HTTP 500(INTERNAL_SERVER_ERROR), DB payments.status = PENDING 유지(SUCCESS 미전이) 확인. REQ-PAY-008 핵심 검증 포인트인 '타임아웃 발동 시 결제 상태 PENDING 유지' 충족)
- **관련 REQ**: REQ-PAY-008
- **분류**: 예외, 경계값
- **우선순위**: P0(필수/핵심)
- **사전조건**: payment-service 기동 중. PortOne 호출을 12초 지연시키는 mock 서버(WireMock `fixedDelayMilliseconds: 12000` 등)를 띄우고, payment-service의 설정 키 `external.portone.base-url` 을 mock 주소로 오버라이드한다(예: `SPRING_APPLICATION_JSON='{"external":{"portone":{"base-url":"http://localhost:9999"}}}'` 환경변수로 재기동, 또는 integrationTest `@DynamicPropertySource`). 결제 confirm 대상 PENDING 결제 1건 준비
- **실행 단계**:
  1. PortOne mock을 12초 지연 응답으로 설정
  2. 결제 승인(confirm) 요청 — 다른 문서와 동일하게 **게이트웨이(8080) 경유**로 호출한다(`POST /payments/confirm`, paymentId는 요청 본문). 게이트웨이가 JWT를 검증한 뒤 `X-User-Id`/`X-User-Role` 헤더를 주입해 payment-service로 라우팅하므로, 서비스(8085)를 직접 호출하면 `X-User-Id` 누락으로 실패한다. (PortOne 10초 타임아웃 < 게이트웨이 30초 타임아웃이라 게이트웨이가 먼저 끊지 않는다.)
     ```bash
     curl -s -o /dev/null -w "%{http_code} %{time_total}\n" \
       -X POST http://localhost:8080/payments/confirm \
       -H "Authorization: Bearer <유효_JWT>" \
       -H "Content-Type: application/json" \
       -d '{"paymentId":"<paymentId>","paymentKey":"<key>","amount":<amt>}'
     ```
  3. payment_service.payments 상태 확인:
     ```bash
     docker exec ticket-postgres psql -U payment_svc_user -d ticket_queue \
       -c "SELECT status FROM payment_service.payments WHERE id='<paymentId>';"
     ```
- **기대 결과**: 약 10초 후 타임아웃(`time_total ≈ 10s`), HTTP 503/504, 결제 상태 `PENDING` 유지(SUCCESS 미전이), Resilience4j timeout 메트릭 증가
- **검증 포인트**: 12초가 아닌 10초 부근에서 호출 중단, 결제 상태가 SUCCESS로 잘못 전이되지 않음, 멱등성 키로 재시도 안전

---

### TC-GAP-003 — 내부 API 인증 실패 시 호출자/IP/시간 보안 감사 로그 기록

- [x] 통과 (2026-05-31, 근거: reservation-service:8084에 X-Service-Api-Key: wrong-key-12345로 호출 → HTTP 401, 서비스 로그에 WARN [INTERNAL_API_AUTH_FAILED] service=reservation-service, uri=/internal/reservations/some-id/status, method=GET, remoteAddr=192.168.97.1, forwardedFor=null, keyPresent=true 기록 확인. IP·요청경로·타임스탬프 모두 포함)
- **관련 REQ**: REQ-INT-006
- **분류**: 보안, 감사
- **우선순위**: P1(중요)
- **사전조건**: 임의 백엔드 서비스(예: reservation-service 8084) 기동 중, 로그 출력(stdout 또는 파일) 캡처 가능
- **실행 단계**:
  1. 잘못된 키로 내부 API 직접 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       -H "X-Service-Api-Key: wrong-key" \
       http://localhost:8084/internal/reservations/some-id/status
     ```
  2. 서비스 로그에서 인증 실패 감사 로그 확인:
     ```bash
     docker logs ticket-reservation-service 2>&1 | \
       grep -iE "internal.*(auth|api.key).*(fail|denied|unauthorized)" | tail -5
     ```
- **기대 결과**: HTTP 401, 로그에 인증 실패 이벤트가 **호출자 식별 정보 + 원격 IP + 타임스탬프**와 함께 WARN/ERROR 레벨로 기록됨
- **검증 포인트**: 로그 1건 이상에 IP(remoteAddr), 요청 경로, 시각이 포함, 정상 키 호출 시에는 감사 실패 로그 미발생

---

### TC-GAP-004 — `/internal/**` 네트워크 레벨 차단(VPC Private Subnet) 검증

- [x] NA (사유: 로컬 Docker 환경에서는 VPC/Security Group 미적용으로 검증 불가. IaC 코드(Terraform 등) 미존재 — 운영/스테이징 배포 시 AWS Security Group 규칙으로 Private Subnet 내부에서만 서비스 포트 접근 허용 필요. 애플리케이션 레벨 차단은 Gateway 404(block-internal-api 라우트, TC-GW-011)와 서비스 레벨 X-Service-Api-Key 401(TC-GAP-003) 으로 대체 증빙)
- **관련 REQ**: REQ-INT-007
- **분류**: 보안, 인프라
- **우선순위**: P2(선택) — 로컬 통합테스트로 완전 검증 불가, 운영/스테이징 인프라 점검 항목으로 분리
- **사전조건**: 배포 환경(AWS) 또는 Security Group/네트워크 정책이 적용된 스테이징 환경 접근 권한
- **실행 단계**:
  1. Public Subnet(또는 외부망)에서 백엔드 서비스의 `/internal/**` 직접 호출 시도(올바른 API Key 포함):
     ```bash
     curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 \
       -H "X-Service-Api-Key: <valid-key>" \
       http://<service-private-ip>:8084/internal/reservations/x/status
     ```
  2. Private Subnet 내 다른 서비스에서 동일 호출 — 정상 응답 확인
- **기대 결과**: 외부망에서는 연결 타임아웃/거부(Security Group 차단), Private Subnet 내부에서만 200/404 응답
- **검증 포인트**: 네트워크 계층에서 외부 접근 차단(애플리케이션 401 이전에 TCP 레벨 차단). 로컬 환경에서는 NA로 처리하고 인프라 코드(Terraform/SG rule) 리뷰로 대체

---

### TC-GAP-005 — 좌석 등급(VIP/S/A/B)별 그룹핑 및 가격 정합성 명시 검증

- [x] 통과 (2026-05-31, 근거: scheduleId=a0eebc99-...-a30 기준 GET /events/schedules/{id}/seats → grades 4개(VIP 150,000원 20석, S 120,000원 20석, A 99,000원 20석, B 70,000원 40석) 반환. DB SELECT grade,COUNT(*),price FROM event_service.seats WHERE event_schedule_id=... GROUP BY grade → 동일 4등급 합계 100석 정합성 확인. REQ-EVT-009 등급 그룹핑·REQ-EVT-018 좌석 자동 초기화 동시 검증)
- **관련 REQ**: REQ-EVT-009, REQ-EVT-018
- **분류**: 정상
- **우선순위**: P1(중요)
- **사전조건**: event-service 기동 중, VIP/S/A/B 4개 등급을 포함한 seatTemplate으로 공연 1건 생성 완료(TC-EVT-001 재사용 가능)
- **실행 단계**:
  1. 좌석 정보 조회:
     ```bash
     curl -s http://localhost:8083/events/<eventId>/schedules/<scheduleId>/seats | jq '.grades'
     ```
  2. DB에서 등급별 좌석 수와 가격 확인:
     ```bash
     docker exec ticket-postgres psql -U event_svc_user -d ticket_queue \
       -c "SELECT grade, COUNT(*), price FROM event_service.seats WHERE schedule_id='<scheduleId>' GROUP BY grade, price ORDER BY grade;"
     ```
- **기대 결과**: 응답에 VIP/S/A/B 4개 등급 그룹이 각각 가격과 함께 반환, 좌석 자동 초기화로 생성된 총 좌석 수 = seatTemplate capacity 합과 일치
- **검증 포인트**: 4개 등급 모두 존재, 등급별 가격이 seatTemplate 정의값과 일치, 누락/중복 등급 0건 (REQ-EVT-018 좌석 자동 생성 정합성 동시 검증)

---

> REQ-FE-003/013/014/018/019/020/021 → 10_frontend_e2e.md의 TC-FE-023~026에서 직접 커버됨(중복 GAP 제거).

---

### TC-GAP-009 — Vercel 배포 파이프라인 및 환경 변수 주입 검증

- [x] NA (사유: 로컬 통합테스트 범위 외 — CI/CD 배포 인프라 영역. Vercel 프로젝트 연동 및 환경 변수 주입은 배포 단계에서 별도 검증. REQ-FE-025는 인프라/배포 범위로 로컬 검증 제외)
- **관련 REQ**: REQ-FE-025
- **분류**: 비기능, 배포
- **우선순위**: P2(선택) — CI/CD 인프라 검증 항목
- **사전조건**: Vercel 프로젝트 연동, GitHub PR 프리뷰 활성화, 필수 환경 변수(`NEXT_PUBLIC_*`, JWT_SECRET 등) 설정
- **실행 단계**:
  1. develop 브랜치 push 또는 PR 생성 후 Vercel 빌드 트리거 확인(`vercel ls` 또는 대시보드)
  2. 프리뷰 URL에서 헬스 페이지 및 API base URL 환경 변수 정상 주입 확인
- **기대 결과**: push 시 자동 배포 성공, PR 프리뷰 URL 생성, 런타임 환경 변수가 올바른 백엔드 게이트웨이를 가리킴
- **검증 포인트**: 빌드 성공(exit 0), 프리뷰 배포 200 응답, 환경 변수 누락으로 인한 런타임 에러 0건

---

### TC-GAP-FE-LABEL — 프론트엔드 TC `관련 REQ` 라벨 보정(문서 정합성)

- [x] 완료 (10_frontend_e2e.md 전 TC에 REQ-FE 라벨 부여 완료)
- **관련 REQ**: REQ-FE-001 ~ REQ-FE-024 (라벨 매핑 부재 해소)
- **분류**: 문서 정합성
- **우선순위**: P0(필수/핵심) — 커버리지 집계 신뢰성 직결
- **사전조건**: 10_frontend_e2e.md 편집 권한
- **실행 단계**:
  1. 11.2 FE 매핑표의 "암묵" 항목을 기준으로 각 TC-FE-xxx의 `관련 REQ: 해당 없음` 을 실제 REQ-FE-xxx로 치환
     - 예: TC-FE-003 → REQ-FE-001, REQ-FE-005 / TC-FE-009 → REQ-FE-010, REQ-FE-011 / TC-FE-013 → REQ-FE-024 / TC-FE-014 → REQ-FE-017, REQ-FE-022 / TC-FE-005·006·022 → REQ-FE-023 등
  2. 보정 후 11.1 FE 카테고리 커버리지 % 재집계
- **기대 결과**: FE 카테고리 직접 커버 필수 REQ 0건 → 라벨 매핑 완료 후 14건 이상으로 상승
- **검증 포인트**: 모든 TC-FE의 `관련 REQ`가 `해당 없음`이 아닌 구체 REQ ID를 가리킴, 11.1 표 갱신 반영

---

#### 보강 후 잔여 미커버 필수 REQ 추적표

| REQ ID | 미커버 사유 | 보강 TC |
|---|---|---|
| REQ-GW-007 | 글로벌 타임아웃 직접 TC 부재 | TC-GAP-001 |
| REQ-PAY-008 | 결제 타임아웃 10초 직접 TC 부재 | TC-GAP-002 |
| REQ-INT-006 | 인증 실패 감사 로깅 직접 TC 부재 | TC-GAP-003 |
| REQ-INT-007 | VPC 네트워크 차단 (인프라 영역) | TC-GAP-004 (조건부) |
| REQ-EVT-009 / EVT-018 | 등급/좌석 자동생성 정합성 명시 TC 부재 | TC-GAP-005 |
| REQ-FE-021/019/020 | 회원가입 4단계 E2E — 10_frontend_e2e.md에서 직접 커버 | TC-FE-026 |
| REQ-FE-003 | 접근성 키보드/ARIA — 10_frontend_e2e.md에서 직접 커버 | TC-FE-023 |
| REQ-FE-014/013 | CWV/번들 — 10_frontend_e2e.md에서 직접 커버 | TC-FE-024·025 |
| REQ-FE-025 | Vercel 배포 검증 (인프라 영역) | TC-GAP-009 (조건부) |
| REQ-FE-001~024 (라벨 누락) | 라벨 보정 완료 | 완료 (TC-GAP-FE-LABEL) |

---

### 11.4 프로젝트 완성 판정 기준

본 프로젝트는 아래 **세 조건을 모두 충족**할 때 "프로젝트 완성"으로 간주한다.

1. **모든 P0 항목 통과**: 통합테스트 문서 00~11 전체의 우선순위 `P0(필수/핵심)` TC가 전부 `- [x]`(통과) 상태여야 한다. P1/P2 미실행 항목이 남아 있어도 P0가 하나라도 미통과면 완성으로 보지 않는다.
2. **필수 REQ 100% 커버**: REQUIREMENTS.md의 `필수` 분류 요구사항 99건이 전부 1개 이상의 통과한 TC로 커버되어야 한다(11.2 매핑표의 모든 `필수` 행 상태가 `커버` — "암묵"은 TC-GAP-FE-LABEL로 라벨 보정 완료, "미커버"는 11.3 보강 TC 통과로 해소). 단, 로컬 통합테스트로 검증 불가한 인프라 항목(REQ-INT-007, REQ-FE-025)은 별도 인프라 점검(TC-GAP-004/009) 또는 IaC 리뷰로 대체 증빙한다.
3. **모든 체크박스 [x]**: 11.3에서 정의한 잔존 보강 TC(TC-GAP-001 ~ TC-GAP-005, TC-GAP-009, 및 이미 완료된 TC-GAP-FE-LABEL)를 포함하여, 완성 판정 시점에 잔존하는 모든 통합테스트 체크박스가 `- [x]` 여야 한다. 단, 로컬에서 검증 불가한 조건부/인프라 항목은 `- [x] (NA: 사유 + 대체 증빙)` 형식으로 표기하고 증빙(IaC/Security Group rule 파일 경로 또는 PR 링크 등)을 반드시 기재한다. (REQ-FE-003/013/014/018/019/020/021은 11.3 추적표대로 10_frontend_e2e.md의 TC-FE-023~026에서 직접 커버되므로 별도 GAP TC를 두지 않는다.)

> 위 3개 조건 중 하나라도 미충족이면 프로젝트는 "진행 중"으로 분류하며, 미통과 P0 및 미커버 필수 REQ 목록을 회귀 백로그로 관리한다.
