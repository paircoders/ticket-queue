# 운영 및 유지보수

## 1. 모니터링 전략 (포트폴리오 중심)

**전략:** 
복잡한 AWS CloudWatch 비용과 설정을 줄이고, **Docker Compose** 환경에서 즉시 사용 가능한 오픈소스 도구를 활용하여 시스템 상태와 메시지 흐름을 시각화합니다. 이는 면접이나 데모 시 시스템 동작을 효과적으로 보여주는 데 유리합니다.

### 1.1 Kafka 모니터링 (Kafka UI)

**도구:** `provectus/kafka-ui`
**목적:** Kafka 토픽, 메시지, Consumer Group 상태 시각화

**확인 항목:**
- **Topic 데이터 확인:** 결제 완료(`payment.success`) 이벤트가 정상적으로 발행되었는지 확인.
- **Consumer Lag:** 메시지 처리 지연이 발생하고 있는지 시각적으로 확인.
- **Message Flow:** Producer에서 Consumer로 데이터가 흐르는 과정을 데모 시연.

### 1.2 시스템 리소스 모니터링

**도구:** `ctop` 또는 `docker stats`
**목적:** 컨테이너별 CPU/Memory 사용량 실시간 확인

```bash
# 실시간 리소스 사용량 확인
docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"
```

### 1.3 애플리케이션 로그

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

**관련 요구사항:** REQ-EVT-017, REQ-EVT-027, REQ-EVT-031

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
