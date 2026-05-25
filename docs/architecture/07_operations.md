# 운영 및 유지보수

## 1. 모니터링 전략

**전략:** 
복잡한 AWS CloudWatch 비용과 설정을 줄이고, **Docker Compose** 환경에서 즉시 사용 가능한 오픈소스 도구를 활용하여 시스템 상태와 메시지 흐름을 시각화합니다. 이는 데모 시 시스템 동작을 효과적으로 보여주는 데 유리

### 1.1 Kafka 모니터링 (Kafka UI)

**도구:** `provectus/kafka-ui`
**목적:** Kafka 토픽, 메시지, Consumer Group 상태 시각화

**확인 항목:**
- **Topic 데이터 확인:** 결제 완료(`payment.success`) 이벤트가 정상적으로 발행되었는지 확인.
- **Consumer Lag:** 메시지 처리 지연이 발생하고 있는지 시각적으로 확인.
- **Message Flow:** Producer에서 Consumer로 데이터가 흐르는 과정을 데모 시연.

### 1.2 애플리케이션 메트릭 모니터링 (Prometheus + Grafana)

**도구:** `prom/prometheus:v3.2.1` + `grafana/grafana:11.4.0`
**목적:** 6개 마이크로서비스의 QPS, 레이턴시(P50/P95/P99), 에러율, JVM 상태 실시간 관측

**접근 URL:**
- Prometheus: `http://localhost:9090` (메트릭 쿼리 및 타겟 상태 확인)
- Grafana: `http://localhost:3001` (로컬 전용 기본값: admin / admin — 공유/스테이징/운영 환경에서는 반드시 변경)
  - 비밀번호 변경: Grafana UI → Server Admin → Users → admin → Change Password
  - 또는 `docker/secrets/grafana_admin_pw.txt` 파일에 강력한 비밀번호 설정 후 컨테이너 재시작

**메트릭 수집 방식:**
- 각 서비스 `/actuator/prometheus` 엔드포인트에서 15초 간격 스크래핑
- Micrometer Prometheus Registry를 통해 JVM, HTTP, DB, Kafka 메트릭 자동 노출
- `host.docker.internal`을 통해 컨테이너 → 호스트 서비스 접근

**자동 프로비저닝 대시보드 (4개):**

| 대시보드 | 주요 메트릭 | 폴더 |
|---------|-----------|------|
| JVM & Spring Boot Overview | 힙 메모리, GC 일시정지, CPU, 스레드 수 | Ticket Queue |
| HTTP Requests Overview | QPS, P50/P95/P99 레이턴시, 5xx 에러율, 상태코드별 분포 | Ticket Queue |
| Resilience4j Circuit Breaker | CB 상태(CLOSED/OPEN/HALF_OPEN), 실패율, 호출 수 | Ticket Queue |
| Infrastructure Overview | HikariCP 커넥션 풀, Kafka Consumer Lag, 처리량 | Ticket Queue |

**Prometheus Alert 규칙 (계획됨 — 미배포):**

> 아래 Alert 규칙은 설계 단계이며, Prometheus alert rule 파일 및 Alertmanager는 아직 구성되지 않았습니다.
> 배포 시 `docker/prometheus/alert_rules.yml` 생성 및 `prometheus.yml`에 `rule_files:` 섹션 추가가 필요합니다.

#### Redis Blacklist CircuitBreaker OPEN 알림
- **쿼리**: `resilience4j_circuitbreaker_state{name="redisBlacklist", state="open"} == 1`
- **심각도**: WARNING
- **CB 설정**: `waitDurationInOpenState: 30s`, `minimumNumberOfCalls: 20`, 실패율 기반 트립 (slow-call 미사용)
- **보안 영향**: 서킷 OPEN 시 fail-open 정책으로 로그아웃된 Access Token이 최대 30초간 허용될 수 있음 (상세: `docs/architecture/06_api_security.md` §2.1.1)
- **대응**:
  1. Redis 상태 확인: `docker exec ticket-valkey valkey-cli ping`
  2. HALF_OPEN 전환 확인: `resilience4j_circuitbreaker_state{name="redisBlacklist", state="half_open"} == 1`
  3. CLOSED 복구 확인: `resilience4j_circuitbreaker_state{name="redisBlacklist", state="closed"} == 1`

#### PortOne CircuitBreaker OPEN 알림 (REQ-PAY-009)
- **쿼리**: `resilience4j_circuitbreaker_state{name="portone-v2-client", state="open"} == 1`
- **심각도**: CRITICAL — PortOne 결제 게이트웨이 차단으로 신규 결제·확인 모두 503 PORTONE_CIRCUIT_OPEN 응답.
- **CB 설정**: `waitDurationInOpenState: 60s`, `failureRateThreshold: 50`, `slidingWindowSize: 100`, `minimumNumberOfCalls: 5`, `permittedNumberOfCallsInHalfOpenState: 3` (`payment-service/application.yml`)
- **결제 영향**:
  - `POST /payments` pre-register 실패 → Payment row 가 `FAILED` 상태 + `PaymentFailedEvent` Outbox 발행, 클라이언트 재시도 권장.
  - `POST /payments/confirm` 차단 → Payment 는 `PENDING` 유지, hold_expires_at 만료 시 `#54` 배치가 자동 취소.
- **대응**:
  1. PortOne 상태 페이지 확인: <https://status.portone.io/>
  2. HALF_OPEN 전환 확인: `resilience4j_circuitbreaker_state{name="portone-v2-client", state="half_open"} == 1`
  3. CLOSED 복구 확인: `resilience4j_circuitbreaker_state{name="portone-v2-client", state="closed"} == 1`

**서비스별 포트 매핑 (Prometheus 스크래핑 대상):**

| 서비스 | 서비스 포트 | Management 포트 | 메트릭 경로 |
|--------|-----------|----------------|-----------|
| api-gateway | 8080 | 9080 | `/actuator/prometheus` |
| user-service | 8081 | 9081 | `/actuator/prometheus` |
| event-service | 8082 | 9082 | `/actuator/prometheus` |
| queue-service | 8083 | 9083 | `/actuator/prometheus` |
| reservation-service | 8084 | 9084 | `/actuator/prometheus` |
| payment-service | 8085 | 9085 | `/actuator/prometheus` |

> **보안 참고:** Actuator 엔드포인트는 management 전용 포트(908X)에서만 노출됩니다. 서비스 메인 포트(808X)로는 `/actuator/**` 접근이 불가합니다.

**실행 방법:**

```bash
# Prometheus + Grafana 시작
docker-compose up -d prometheus grafana

# 타겟 상태 확인 (서비스 기동 후)
echo "브라우저에서 접속: http://localhost:9090/targets"

# Grafana 대시보드 확인
echo "브라우저에서 접속: http://localhost:3001"
```

**관련 요구사항:** REQ-GW-012 (라우트별 요청 수, 응답 시간, 에러율 메트릭)

---

### 1.3 시스템 리소스 모니터링

**도구:** `ctop` 또는 `docker stats`
**목적:** 컨테이너별 CPU/Memory 사용량 실시간 확인

```bash
# 실시간 리소스 사용량 확인
docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"
```

### 1.4 애플리케이션 로그

**도구:** `docker-compose logs`
**전략:** 중앙화된 로깅 시스템(ELK) 구축 대신, 컨테이너 로그를 직접 확인하거나 파일로 저장하여 분석.

```bash
# 전체 로그 확인 (실시간)
docker-compose logs -f

# 특정 서비스 로그 확인 (예: Payment Service)
docker-compose logs -f payment-service
```

---

## 2. 성능 최적화 전략

### 2.1 Redis 캐싱

**Cache-Aside 패턴:** 조회 시 캐시 확인 → 없으면 DB 조회 → 캐시 저장
**TTL:** 공연 목록/상세 5분, 오픈 시 1분으로 동적 조정
**Cache Stampede 방지:** Lua 스크립트 락

**관련 요구사항:** REQ-EVT-017, REQ-EVT-021, REQ-EVT-024

### 2.2 DB 쿼리 최적화

**인덱스 전략:**
- `seats (event_id, status)`
- `reservations (user_id, status)`
- `outbox_events (published, created_at)`

**N+1 문제 해결:** JPA Fetch Join

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

**도구:** k6 / JMeter
**시나리오:**
- 대기열 동시성 테스트 (50,000명 가정 부하)
- 좌석 선점 경쟁 테스트 (Redis 분산락 검증)
- 결제 처리량 테스트

---

## 4. 장애 대응 프로세스

**간소화된 대응:**
- **서비스 다운:** `docker-compose restart <service_name>`
- **데이터 불일치:** SAGA 보상 트랜잭션 로그 확인 및 수동 보정 (Admin API 활용)
- **배포 롤백:** 이전 Docker Image Tag로 `docker-compose up -d`
