# 운영 및 유지보수

## 1. 모니터링 및 로깅

### 1.1 CloudWatch 통합

**로그 수집:** 5GB/월 무료티어 활용
**로그 보관:** 1일 (중요 로그만 7일)
**JSON 구조화:** 파싱 용이

### 1.2 핵심 메트릭 (무료티어 10개 이내)

1. API Gateway: 요청 수, 응답 시간, 에러율
2. 대기열: 대기 인원, 승인률
3. 예매: 선점 성공/실패율
4. 결제: 성공/실패율
5. RDS: CPU, 메모리, 커넥션 수
6. Redis: 메모리 사용량, Hit Rate

### 1.3 알람 설정 (무료티어 10개)

1. 서비스 다운 (헬스체크 실패)
2. 에러율 5% 이상
3. RDS CPU 80% 이상
4. Redis 메모리 80% 이상

### 1.4 분산 추적

**Spring Cloud Sleuth:** Request ID 전파
**CloudWatch Logs Insights:** 로그 조회 및 분석

---

## 2. 성능 최적화 전략

### 2.1 Redis 캐싱

**Cache-Aside 패턴:** 조회 시 캐시 확인 → 없으면 DB 조회 → 캐시 저장
**TTL:** 공연 목록/상세 5분, 오픈 시 1분으로 동적 조정
**Cache Stampede 방지:** Lua 스크립트 락

**관련 요구사항:** REQ-EVT-017, REQ-EVT-027, REQ-EVT-031

### 2.2 DB 쿼리 최적화

**인덱스 전략:**
- `seats (event_id, status)`
- `reservations (user_id, status)`
- `outbox_events (published, created_at)`

**N+1 문제 해결:** JPA Fetch Join

### 2.3 API 최적화

**Response 압축:** gzip (1KB 이상)
**페이징:** 기본 20개, 최대 100개

---

## 3. 테스트 전략

### 3.1 단위 테스트

JUnit 5, Mockito, AssertJ
**목표 커버리지:** 70% 이상

### 3.2 통합 테스트

Testcontainers (PostgreSQL, Redis, Kafka)
Spring Boot Test

### 3.3 E2E 테스트 시나리오

**주요 플로우:**
1. 회원가입 → 로그인 → 대기열 진입 → 좌석 선택 → 결제 → 예매 확정

### 3.4 성능 테스트

**도구:** JMeter, Gatling
**시나리오:**
- 대기열 동시성 테스트 (50,000명)
- 좌석 선점 경쟁 테스트
- 결제 처리량 테스트

---

## 4. 운영 계획

### 4.1 SLA/SLO 정의

| 항목 | 목표 | 측정 방법 |
|------|------|----------|
| 가용성 | 99.5% (초기), 99.9% (Multi-AZ 전환 후) | CloudWatch Uptime |
| 응답 시간 (P95) | 대기열 진입 < 100ms, 조회 < 50ms | CloudWatch Metrics |
| 처리량 | 36,000명/시간 (대기열 승인) | Kafka Consumer Lag |
| Redis 가용성 | 99.9% | ElastiCache Metrics |

### 4.2 장애 대응 프로세스

**장애 등급:**
- **P0 (Critical)**: 서비스 전체 다운, 즉시 대응
- **P1 (High)**: 주요 기능 불가 (결제, 예매), 1시간 내 대응
- **P2 (Medium)**: 일부 기능 불가, 4시간 내 대응
- **P3 (Low)**: 경미한 오류, 24시간 내 대응

**On-call 체계:** 주/야간 교대 (개발자 2명 순환)

### 4.3 백업 및 복구

**RDS 백업:**
- 자동 백업: 1일 보관 (무료)
- 수동 스냅샷: 주요 배포 전 생성, 7일 보관

**복구 절차:**
1. 최신 스냅샷 확인
2. 새 RDS 인스턴스로 복구
3. 엔드포인트 변경 (또는 DNS 업데이트)
4. 서비스 재시작

**RTO/RPO:**
- RTO (복구 시간): 30-60분 (Single-AZ), 1-2분 (Multi-AZ)
- RPO (데이터 손실): 최대 5분

### 4.4 용량 계획

**트래픽 예측:**
- 평상시: 100 TPS
- 티켓 오픈: 1,000 TPS (순간 5,000 TPS)

**리소스 증설 기준:**
- RDS CPU 70% 지속 → Read Replica 추가 또는 인스턴스 업그레이드
- Redis 메모리 70% → 인스턴스 업그레이드
- EC2 CPU 70% → Auto Scaling 또는 수동 스케일 아웃

**비용 모니터링:**
- AWS Cost Explorer로 월별 비용 추적
- 알람: 예상 비용 초과 시 알림
