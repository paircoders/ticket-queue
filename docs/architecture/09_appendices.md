# 부록 및 기타

## 1. 기술 부채 및 개선 사항

### 1.1 알려진 제약사항

1. **단일 RDS 인스턴스:**
   - 서비스별 물리적 DB 분리 미구현
   - 향후 트래픽 증가 시 병목 가능

2. **Single-AZ 배포:**
   - AZ 장애 시 서비스 중단
   - 초기 비용 절감을 위한 선택

3. **Kafka 단일 브로커 (초기 MVP):**

#### 현재 구성
- EC2 t2.micro 단일 브로커
- 토픽: payment.events, reservation.events, DLQ (각 1 partition, replication-factor 1)

#### 장애 영향 범위
- **이벤트 처리 중단**: 결제 성공 → 예매 확정 불가
- **데이터 유실 위험**: 브로커 디스크 손상 시 미발행 메시지 손실
- **복구 시간**: 30-60분 (수동 재시작)

#### 재해 복구 계획 (Disaster Recovery Plan)

**RTO (Recovery Time Objective): 30분**
**RPO (Recovery Point Objective): 최대 1분 (Outbox 재발행으로 복구)**

**장애 감지:**
1. CloudWatch Alarm: Kafka JMX 메트릭 중단 (5분 연속)
2. Health Check 실패: Event Service → Kafka connection timeout
3. 알람 전송: Slack + 담당자 SMS

**복구 절차:**
1. **즉시 조치 (0-5분)**
   - EC2 인스턴스 재시작: `sudo systemctl restart kafka`
   - Zookeeper 상태 확인: `echo stat | nc localhost 2181`

2. **데이터 복구 (5-15분)**
   - Outbox Poller 재시작: 미발행 이벤트 재전송
   - Kafka 로그 무결성 확인: `kafka-run-class.sh kafka.tools.DumpLogSegments`

3. **Consumer 재시작 (15-30분)**
   - 모든 Consumer 그룹 리셋 (필요 시)
   - Lag 모니터링: `kafka-consumer-groups.sh --describe`

4. **사후 대응**
   - 고객 공지: "일시적 예매 지연 발생, 복구 완료"
   - 영향받은 예매: Reservation 상태 PENDING → 수동 확인 후 CONFIRMED

**데이터 유실 방지 (Outbox Pattern):**
- Kafka 장애 중에도 Outbox 테이블에 이벤트 저장됨
- 브로커 복구 후 Outbox Poller가 자동 재전송
- 최대 데이터 유실: 0건 (DB 기반)

#### 무료티어 종료 후 비용 계획 (12개월 경과 시)

**현재 월별 비용 (무료티어 적용):**
- **총 비용**: $12-17/month
- RDS PostgreSQL (db.t3.micro, 750시간/월 무료): $0
- ElastiCache Redis (cache.t2.micro, 750시간/월 무료): $0
- EC2 Kafka (t2.micro, 750시간/월 무료): $0
- ECS Fargate (6 서비스 × 1 vCPU, 0.5GB): $12.17/month (무료티어 소진)
- Application Load Balancer: $16.20/month
- 기타 (CloudWatch, S3): $1-2/month

**12개월 후 비용 (무료티어 종료, 최적화 미적용):**

| 항목 | 스펙 | 월 비용 | 설명 |
|------|------|--------|------|
| **RDS PostgreSQL** | db.t3.micro, Single-AZ, 20GB SSD | $17.89 | 무료티어 종료 ($0.017/시간 × 730시간 + $2.30 스토리지) |
| **ElastiCache Redis** | cache.t2.micro, Single-AZ | $11.52 | 무료티어 종료 ($0.017/시간 × 730시간) |
| **EC2 Kafka** | t2.micro, Single-AZ, 20GB EBS | $8.93 | 무료티어 종료 ($0.0116/시간 × 730시간 + $2 EBS) |
| **ECS Fargate** | 6 서비스 × 1 vCPU × 0.5GB | $12.17 | 동일 (무료티어 없음) |
| **Application Load Balancer** | Single ALB | $16.20 | 동일 ($0.0225/시간 + 데이터 전송) |
| **CloudWatch Logs** | 5GB/월 (무료 5GB 종료) | $2.50 | $0.50/GB × 5GB (초과분만) |
| **Route 53** | Hosted Zone 1개 + Health Check 1개 | $1.00 | $0.50/hosted zone + $0.50/health check |
| **기타 (S3, Data Transfer)** | - | $1.18 | ECS 로그, ALB 액세스 로그 |
| **합계** | - | **$71.39** | 5.9배 증가 ($12.17 → $71.39) |

**최적화 적용 후 비용 (권장):**

| 최적화 방법 | 적용 대상 | 절감액 | 적용 후 비용 |
|-----------|----------|-------|------------|
| **Reserved Instances (RI)** | RDS, ElastiCache | -$12.17 (41% 할인) | $17.24 |
| **Spot Instances** | EC2 Kafka (t2.micro) | -$4.90 (70% 할인) | $4.03 |
| **Savings Plans** | ECS Fargate (1년 약정) | -$4.87 (40% 할인) | $7.30 |
| **CloudWatch Logs 보존 단축** | 7일 보존 (기존 30일) | -$1.88 (75% 절감) | $0.62 |
| **ALB 유지 (단일 사용)** | NAT Gateway 제거로 이미 최적화 | $0 | $16.20 |
| **합계 절감액** | - | **-$23.40** | - |
| **최적화 후 월 비용** | - | - | **$47.99** |

**비용 절감 효과:**
- **최적화 전**: $71.39/월
- **최적화 후**: $47.99/월
- **절감액**: $23.40/월 (32.8% 절감)
- **연간 절감액**: $280.80/년

**구체적인 최적화 조치:**

**1. Reserved Instances (RI) 구매 (무료티어 만료 1개월 전)**
- **대상**: RDS db.t3.micro, ElastiCache cache.t2.micro
- **약정 기간**: 1년 (No Upfront)
- **할인율**: 41%
- **적용 시점**: 무료티어 만료 1개월 전 구매 (자동 적용)
- **AWS CLI 예시**:
```bash
aws rds purchase-reserved-db-instances-offering \
  --reserved-db-instances-offering-id <offering-id> \
  --reserved-db-instance-id ticketing-rds-ri-2027 \
  --db-instance-count 1

aws elasticache purchase-reserved-cache-nodes-offering \
  --reserved-cache-nodes-offering-id <offering-id> \
  --reserved-cache-node-id ticketing-redis-ri-2027 \
  --cache-node-count 1
```

**2. Spot Instances (EC2 Kafka)**
- **대상**: EC2 t2.micro (Kafka 브로커)
- **할인율**: 평균 70% (변동 가능)
- **리스크**: 중단 가능 (평균 중단률 < 5%)
- **중단 대응**:
  - Spot Instance Interruption Notification (2분 전 알림)
  - 자동 재시작 (User Data 스크립트로 Kafka 자동 시작)
  - Outbox Pattern으로 메시지 유실 방지
- **적용 방법**: Launch Template에서 Spot 요청 설정

**3. Savings Plans (ECS Fargate)**
- **대상**: ECS Fargate (6 서비스)
- **약정 기간**: 1년 Compute Savings Plan
- **할인율**: 40%
- **유연성**: EC2, Lambda로도 전환 가능 (Compute Savings Plan)
- **적용 시점**: 무료티어 만료 1개월 전 구매

**4. CloudWatch Logs 보존 단축**
- **현재**: 30일 보존 (기본)
- **변경**: 7일 보존 (운영 로그), 90일 보존 (결제 관련)
- **절감액**: 75% (5GB → 1.25GB)
- **적용 방법**:
```bash
aws logs put-retention-policy \
  --log-group-name /ecs/ticketing/api-gateway \
  --retention-in-days 7

aws logs put-retention-policy \
  --log-group-name /ecs/ticketing/payment-service \
  --retention-in-days 90
```

**무료티어 종료 대응 타임라인:**

| 시점 | 조치 사항 | 책임자 | 상태 |
|------|----------|--------|------|
| **D-180 (6개월 전)** | CloudWatch Billing Alert 설정 ($50/월 임계값) | DevOps | 필수 |
| **D-180** | AWS Cost Explorer 활성화, 월별 비용 추적 | DevOps | 필수 |
| **D-90 (3개월 전)** | RI/Savings Plans 구매 여부 결정 | CTO | 필수 |
| **D-90** | Spot Instances 테스트 (Kafka 중단 영향 평가) | A개발자 | 권장 |
| **D-30 (1개월 전)** | RI 구매 (RDS, ElastiCache) | DevOps | 필수 |
| **D-30** | Savings Plans 구매 (ECS Fargate) | DevOps | 필수 |
| **D-30** | CloudWatch Logs 보존 정책 변경 | DevOps | 권장 |
| **D-7 (1주 전)** | Spot Instances 전환 (Kafka) | DevOps | 권장 |
| **D-Day (무료티어 종료일)** | 청구 금액 모니터링 (Cost Explorer) | DevOps | 필수 |
| **D+7 (1주 후)** | 실제 청구 금액 검증, 예상치와 비교 | DevOps | 필수 |
| **D+30 (1개월 후)** | 최적화 효과 검증, 추가 절감 방법 검토 | CTO | 권장 |

**장기 비용 예측 (트래픽 10배 증가 시):**

| 항목 | 현재 스펙 (MVP) | 10배 트래픽 스펙 | 월 비용 |
|------|----------------|-----------------|--------|
| **RDS PostgreSQL** | db.t3.micro (1 vCPU, 1GB) | db.r5.large (2 vCPU, 16GB) + Read Replica 1대 | $283.80 |
| **ElastiCache Redis** | cache.t2.micro (0.5GB) | cache.r5.large (12.3GB) + Replica 1대 | $234.60 |
| **EC2 Kafka** | t2.micro (1 broker) | m5.large × 3 (3-broker cluster) | $223.20 |
| **ECS Fargate** | 6 서비스 × 1 vCPU × 0.5GB | 6 서비스 × 2 vCPU × 1GB × 3 인스턴스 | $73.02 |
| **Application Load Balancer** | Single ALB | Single ALB (데이터 전송 증가) | $21.60 |
| **합계** | - | - | **$836.22** |

**10배 트래픽 시 비용 절감 전략:**
1. **Reserved Instances (3년 약정)**: 62% 할인 → $517/월
2. **Aurora Serverless v2 전환**: RDS 비용 40% 절감 → $170/월
3. **Lambda 전환 (일부 서비스)**: Fargate 비용 60% 절감 → $29/월
4. **총 예상 비용 (최적화 후)**: ~$500/월

**비용 알람 설정 (필수):**
```bash
# CloudWatch Billing Alarm (월 $50 초과 시)
aws cloudwatch put-metric-alarm \
  --alarm-name ticketing-billing-alert-50 \
  --alarm-description "Alert when monthly bill exceeds $50" \
  --metric-name EstimatedCharges \
  --namespace AWS/Billing \
  --statistic Maximum \
  --period 21600 \
  --evaluation-periods 1 \
  --threshold 50 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=Currency,Value=USD \
  --alarm-actions arn:aws:sns:us-east-1:123456789012:billing-alerts

# 월 $100 초과 시 Critical
aws cloudwatch put-metric-alarm \
  --alarm-name ticketing-billing-critical-100 \
  --threshold 100 \
  --alarm-actions arn:aws:sns:us-east-1:123456789012:billing-critical
```

**현재 결론 (2026-01-12):**
- MVP 단계에서는 무료티어 최대 활용 ($12-17/월)
- 12개월 후 RI/Savings Plans 구매로 $47.99/월 목표
- 트래픽 10배 증가 시 $500/월 예상 (최적화 적용 시)
- 장기적으로 Serverless 아키텍처 전환 검토 (비용 효율성)

#### 향후 고가용성 전환 계획 (6개월 내)
- 3-broker 클러스터 (Multi-AZ)
- Replication factor 2 (리더 + 팔로워)
- RTO: 5분 (자동 failover)
- RPO: 0건 (동기 복제)

4. **수동 스케일링:**
   - Auto Scaling 미적용 (비용 절감)
   - 트래픽 예측 필요

### 1.2 향후 개선 계획

**Phase 1 (6개월):**
- Multi-AZ 전환 (RDS, Redis, Kafka)
- Auto Scaling 적용
- Read Replica 추가

**Phase 2 (1년):**
- 서비스별 물리적 DB 분리
- ElasticSearch 도입 (예매 내역 검색)
- CDN 최적화 (정적 리소스)

**Phase 3 (2년):**
- Multi-Region 배포
- Serverless 전환 검토 (Lambda, Aurora Serverless)
- AI 기반 부정 예매 탐지

### 1.3 기술 검토 항목

| 항목 | 현재 | 검토 사항 | 우선순위 |
|------|------|----------|---------|
| CQRS 패턴 | 부분 적용 (Redis 캐싱) | 전면 적용 검토 | Medium |
| Event Sourcing | 미적용 | 이벤트 히스토리 추적 | Low |
| GraphQL | 미적용 | REST API 대체 검토 | Low |
| gRPC | 미적용 | 서비스 간 통신 성능 향상 | Medium |
| Kubernetes | ECS/EC2 | 오케스트레이션 개선 | Low |
| Service Mesh | 미적용 | Istio, Linkerd 검토 | Low |

#### 1.3.1 CQRS 패턴 전환 조건 (정량화)

**현재 상태:**
- Event Service에서 부분적 CQRS 적용 (Redis 캐시로 읽기 최적화)
- 쓰기와 읽기는 동일 PostgreSQL 인스턴스 사용

**전환 고려 필요 시점 (트리거 조건):**

| 지표 | 현재 수용 한계 | 전환 임계값 | 측정 방법 | 책임자 |
|------|-------------|----------|----------|--------|
| **공연 조회 TPS** | < 500 TPS | > 1000 TPS | CloudWatch Custom Metric: `events.read.tps` | A개발자 |
| **Cache Miss Rate** | < 5% | > 20% | Redis INFO: `keyspace_misses / (keyspace_hits + keyspace_misses)` | A개발자 |
| **좌석 조회 P95 응답시간** | < 300ms | > 500ms | CloudWatch ALB `TargetResponseTime` (p95) | A개발자 |
| **DB Read Replica Lag** | 없음 (단일 인스턴스) | > 10초 | RDS Metric: `ReplicaLag` | DevOps |
| **예매 내역 조회 TPS** | < 1000/분 | > 10,000/분 | CloudWatch Custom Metric: `reservations.search.tps` | B개발자 |

**전환 결정 기준:**
- **1개 이상 임계값 초과**: Read Replica 추가 검토
- **3개 이상 임계값 초과 OR TPS > 2000**: 전면 CQRS 전환 검토
- **5개 모두 초과**: CQRS + Event Sourcing 검토

**CQRS 전환 단계별 계획:**

**Phase 1: Read Replica 추가 (1개 임계값 초과 시)**
- **대상 서비스**: Event Service, Reservation Service
- **구성**: PostgreSQL RDS Read Replica 1대 (db.t3.micro)
- **비용 증가**: +$17/month (RDS db.t3.micro, Single-AZ)
- **효과**: 읽기 트래픽 50% 분산, 쓰기 성능 영향 없음
- **마이그레이션 시간**: 1-2일 (Spring Boot `@ReadOnlyTransaction` 라우팅 설정)

**Phase 2: 전면 CQRS with ElasticSearch (3개 이상 임계값 초과 시)**
- **대상 서비스**: Event Service (공연/좌석 검색), Reservation Service (예매 내역 검색)
- **구성**:
  - **읽기 모델**: ElasticSearch 7.x (AWS OpenSearch Service, t3.small.search)
  - **쓰기 모델**: PostgreSQL (기존)
  - **동기화**: Kafka 이벤트 기반 (EventCreated, SeatStatusChanged)
- **비용 증가**: +$15/month (OpenSearch t3.small.search, Single-AZ)
- **효과**:
  - 공연 검색 응답시간 300ms → 50ms (P95)
  - 복잡한 필터링 쿼리 지원 (공연명, 날짜 범위, 장소, 가격대)
- **마이그레이션 시간**: 4-6주 (스키마 설계, Consumer 구현, 데이터 마이그레이션)

**Phase 3: Event Sourcing (선택, 5개 모두 초과 시)**
- **대상 서비스**: Reservation Service (예매 생애주기 추적)
- **구성**:
  - **이벤트 스토어**: PostgreSQL 전용 테이블 (`reservation_events`)
  - **현재 상태**: Materialized View 또는 별도 테이블
- **비용 증가**: +$0 (기존 RDS 활용)
- **효과**:
  - 예매 상태 변경 이력 추적 (PENDING → CONFIRMED → CANCELLED)
  - 감사(Audit) 요구사항 충족
  - 이벤트 재생(Replay)으로 버그 디버깅
- **마이그레이션 시간**: 8-12주 (이벤트 스키마 설계, 재생 로직 구현, 테스트)
- **트레이드오프**: 쓰기 성능 10-20% 저하 (이벤트 INSERT 추가), 저장 공간 2배 증가

**의사결정 프로세스:**
1. **월별 메트릭 리뷰**: 매월 첫째 주 월요일, CloudWatch 대시보드 리뷰
2. **임계값 초과 감지**: CloudWatch Alarm → Slack 알림
3. **팀 미팅**: 2개 이상 임계값 초과 시 기술 검토 회의 소집
4. **전환 결정**: CTO 승인 + 비용 분석 완료 후 진행

**현재 결론 (2026-01-12):**
- MVP 단계에서는 **Phase 1 (Read Replica)도 불필요**
- 트래픽 모니터링 3-6개월 후 재평가
- Redis 캐싱 + DB 인덱스 최적화로 충분히 목표 성능 달성 가능 (P95 < 300ms)

---

## 2. 부록

### 2.1 용어 정의 (Glossary)

| 용어 | 정의 |
|------|------|
| MSA | Microservices Architecture, 마이크로서비스 아키텍처 |
| SAGA | 분산 트랜잭션 패턴, 오케스트레이션 또는 코레오그래피 방식 |
| Outbox Pattern | 이벤트 발행 신뢰성을 보장하는 패턴, DB 트랜잭션과 메시지 발행을 원자적으로 처리 |
| CI/DI | 본인인증 정보, Connecting Information / Duplication Information |
| Queue Token | 대기열 통과 인증 토큰, Reservation Token (qr_xxx), Payment Token (qp_xxx) |
| Sorted Set | Redis 자료구조, Score 기반 정렬된 집합 |
| Redisson | Redis 기반 Java 클라이언트, 분산 락, 분산 자료구조 지원 |
| PortOne | 결제 PG (Payment Gateway), 구 아임포트 |
| reCAPTCHA | Google의 봇 차단 서비스 |
| LocalStack | AWS 서비스 로컬 에뮬레이터 |
| CloudWatch | AWS 모니터링 및 로깅 서비스 |
| ElastiCache | AWS 관리형 Redis/Memcached 서비스 |
| RDS | AWS 관리형 관계형 데이터베이스 서비스 |
| P95 | 95th Percentile, 전체 요청 중 95%가 해당 시간 이내 응답 |
| TPS | Transactions Per Second, 초당 트랜잭션 수 |
| SLA | Service Level Agreement, 서비스 수준 협약 |
| SLO | Service Level Objective, 서비스 수준 목표 |
| RTO | Recovery Time Objective, 목표 복구 시간 |
| RPO | Recovery Point Objective, 목표 복구 시점 (데이터 손실 허용 범위) |

### 2.2 참고 문서 및 링크

**요구사항 명세서:**
- [REQUIREMENTS.md](../REQUIREMENTS.md) - 111개 요구사항 정의

**기술 문서:**
- Spring Cloud Gateway: https://spring.io/projects/spring-cloud-gateway
- Spring Boot 3.x: https://spring.io/projects/spring-boot
- PostgreSQL 18: https://www.postgresql.org/docs/18/
- Redis 7.x: https://redis.io/docs/
- Apache Kafka 3.x: https://kafka.apache.org/documentation/
- Redisson: https://redisson.org/
- Resilience4j: https://resilience4j.readme.io/

**외부 서비스:**
- PortOne: https://portone.io/
- Google reCAPTCHA: https://www.google.com/recaptcha/
- Vercel: https://vercel.com/docs

**AWS 문서:**
- RDS PostgreSQL: https://docs.aws.amazon.com/rds/
- ElastiCache Redis: https://docs.aws.amazon.com/elasticache/
- ECS: https://docs.aws.amazon.com/ecs/
- CloudWatch: https://docs.aws.amazon.com/cloudwatch/
- LocalStack: https://docs.localstack.cloud/

**패턴 및 아키텍처:**
- SAGA 패턴: https://microservices.io/patterns/data/saga.html
- Transactional Outbox: https://microservices.io/patterns/data/transactional-outbox.html
- Event-Driven Architecture: https://martinfowler.com/articles/201701-event-driven.html

### 2.3 아키텍처 결정 기록 (ADR)

**ADR-001: PostgreSQL 단일 인스턴스 + 스키마 분리 선택**
- **날짜:** 2026-01-11
- **상태:** 승인됨
- **컨텍스트:** 비용 최소화하면서 MSA 원칙 준수
- **결정:** 논리적 DB per Service + 물리적 통합
- **결과:** 무료티어 활용, 향후 물리적 분리 가능
- **대안:** 서비스별 독립 RDS (비용 $170+/월)

**ADR-002: EC2 기반 Kafka 선택**
- **날짜:** 2026-01-11
- **상태:** 승인됨
- **컨텍스트:** MSK는 최소 월 $200+로 고비용
- **결정:** EC2 자체 Kafka 클러스터 구축
- **결과:** 월 $15-60, 운영 부담 증가
- **대안:** Amazon SNS/SQS (이벤트 스트리밍 기능 부족), MSK (고비용)

**ADR-003: At-least-once + Consumer 멱등성 선택**
- **날짜:** 2026-01-11
- **상태:** 승인됨
- **컨텍스트:** Exactly-once는 복잡도 증가
- **결정:** At-least-once 전달 보장 + Consumer 멱등성 처리
- **결과:** 신뢰성 확보, 구현 단순
- **대안:** Kafka Transactional API (성능 10-30% 저하)

**ADR-004: Redisson 분산 락 선택**
- **날짜:** 2026-01-11
- **상태:** 승인됨
- **컨텍스트:** 좌석 선점 동시성 제어 필요
- **결정:** Redisson RLock 사용
- **결과:** 안정적인 분산 락, Pub/Sub로 대기 최적화
- **대안:** Redis SET NX (수동 구현, Watch Dog 없음), ZooKeeper (과도한 의존성)

### 2.4 다이어그램 범례 및 표기법

**Mermaid 다이어그램 범례:**

```mermaid
graph LR
    A[사각형: 컴포넌트/서비스]
    B[(원통형: 데이터베이스)]
    C{{다이아몬드: 의사결정}}
    D([라운드 사각형: 외부 시스템])

    A -->|실선 화살표: 동기 호출| B
    A -.->|점선 화살표: 비동기 호출/이벤트| C
    A ==>|굵은 화살표: 주요 플로우| D
```

**시퀀스 다이어그램 표기:**
- `→`: 동기 호출 (Request-Response)
- `-->>`: 비동기 응답
- `-->|메시지|`: 호출 시 메시지

**ERD 표기:**
- `PK`: Primary Key
- `FK`: Foreign Key
- `UK`: Unique Key
- `||--o{`: One-to-Many 관계
- `||--||`: One-to-One 관계

**상태 다이어그램 표기:**
- 원: 상태
- 화살표: 전이 (이벤트/조건)

### 2.5 PortOne 테스트 모드 가이드

**개요:**
Payment Service는 PortOne PG를 통해 결제를 처리합니다. 개발 단계에서는 **테스트 모드 (Test Mode)**를 사용하며, 이 섹션은 전환 절차 및 주의사항을 정의합니다.

#### 2.5.1 PortOne 계정 설정 및 API Key 발급

**1. PortOne 계정 생성**
- PortOne 공식 사이트 (https://portone.io/) 접속
- "회원가입" → 사업자 정보 입력 (사업자등록증 필요)
- 이메일 인증 완료

**2. 가맹점 식별코드(IMP) 발급**
- PortOne 관리자 콘솔 로그인
- "시스템 설정" → "내 식별코드·API Keys" 메뉴
- **테스트 모드**:
  - 가맹점 식별코드: `imp12345678` (자동 발급, 계정마다 고유)
  - API Key: `test_api_key_xxxxx`
  - API Secret: `test_api_secret_xxxxx`

#### 2.5.2 테스트 모드 vs 실제 모드 비교

| 항목 | 테스트 모드 (Test Mode) | 실제 모드 (Production Mode) |
|------|------------------------|---------------------------|
| **가맹점 식별코드** | `imp12345678` (예시) | `imp87654321` (실제 MID) |
| **API Key** | `test_api_key_xxxxx` | `live_api_key_xxxxx` |
| **결제 처리** | 가상 결제 (실제 출금 없음) | 실제 결제 (고객 카드 출금) |
| **테스트 카드 번호** | 사용 가능 (4092-xxxx-xxxx-xxxx) | 사용 불가 (실제 카드만) |
| **PG사 계약** | 불필요 | 필수 (토스페이먼츠, KG이니시스 등) |
| **정산** | 없음 | 실제 정산 (T+2일, 수수료 차감) |
| **결제 금액 제한** | 없음 (1원 결제 가능) | PG사 정책 따름 (최소 100원) |
| **환불** | 즉시 승인 (가상) | PG사 승인 필요 (영업일 기준 1-3일) |
| **Webhook 알림** | 동일 (https://api.example.com/webhooks/portone) | 동일 |
| **PortOne Dashboard** | 테스트 결제 내역 별도 표시 | 실제 결제 내역 |
| **사용 목적** | 개발, QA, 부하 테스트 | 실제 서비스 운영 |

#### 2.5.3 Spring Boot 설정 (환경별 분리)

**application.yml (공통 설정)**
```yaml
# application.yml (src/main/resources)
spring:
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}

portone:
  base-url: https://api.iamport.kr

# Webhook 엔드포인트 (공통)
server:
  port: 8080
  servlet:
    context-path: /payment
```

**application-local.yml (로컬 개발 환경)**
```yaml
# application-local.yml
portone:
  imp-code: imp12345678  # 테스트 가맹점 식별코드
  api-key: ${PORTONE_TEST_API_KEY}  # 환경변수로 주입
  api-secret: ${PORTONE_TEST_API_SECRET}
  mode: test

logging:
  level:
    com.ticketing.payment: DEBUG
    com.portone: DEBUG
```

**application-dev.yml (개발 서버 환경)**
```yaml
# application-dev.yml (AWS ECS Dev)
portone:
  imp-code: imp12345678
  api-key: ${PORTONE_TEST_API_KEY}  # AWS Secrets Manager
  api-secret: ${PORTONE_TEST_API_SECRET}
  mode: test

logging:
  level:
    com.ticketing.payment: INFO
```

logging:
  level:
    com.ticketing.payment: WARN
    com.portone: INFO
```

**Java Configuration (PaymentServiceConfig.java)**
```java
@Configuration
@ConfigurationProperties(prefix = "portone")
public class PortOneConfig {
    private String impCode;
    private String apiKey;
    private String apiSecret;
    private String baseUrl;
    private String mode;  // "test" or "production"

    @Bean
    public PortOneClient portOneClient() {
        if ("production".equals(mode)) {
            log.warn("PortOne PRODUCTION MODE ENABLED - 실제 결제가 처리됩니다!");
        }
        return new PortOneClient(impCode, apiKey, apiSecret, baseUrl);
    }

    // Getters/Setters
}
```

#### 2.5.4 AWS Secrets Manager 저장 및 ECS 주입

**1. Secrets Manager에 API Key 저장**
```bash
# 테스트 모드 (Dev 환경)
aws secretsmanager create-secret \
  --name ticketing/dev/portone \
  --secret-string '{
    "imp_code": "imp12345678",
    "api_key": "test_api_key_xxxxx",
    "api_secret": "test_api_secret_xxxxx"
  }' \
  --region us-east-1

# 실제 모드 (Production 환경)
aws secretsmanager create-secret \
  --name ticketing/prod/portone \
  --secret-string '{
    "imp_code": "imp87654321",
    "api_key": "live_api_key_xxxxx",
    "api_secret": "live_api_secret_xxxxx"
  }' \
  --region us-east-1
```

**2. ECS Task Definition에 환경변수 주입**
```json
{
  "family": "payment-service-prod",
  "containerDefinitions": [
    {
      "name": "payment-service",
      "image": "123456789012.dkr.ecr.us-east-1.amazonaws.com/payment-service:1.0.0",
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "prod"
        }
      ],
      "secrets": [
        {
          "name": "PORTONE_LIVE_API_KEY",
          "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:ticketing/prod/portone:api_key::"
        },
        {
          "name": "PORTONE_LIVE_API_SECRET",
          "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:ticketing/prod/portone:api_secret::"
        }
      ]
    }
  ]
}
```

**3. ECS Task Role 권한 추가**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": [
        "arn:aws:secretsmanager:us-east-1:123456789012:secret:ticketing/prod/portone-*"
      ]
    }
  ]
}
```

#### 2.5.6 모니터링 및 검증

**1. CloudWatch Logs Insights 쿼리 (결제 실패 모니터링)**
```sql
fields @timestamp, payment_key, amount, status, error_message
| filter @logGroup = "/ecs/ticketing/payment-service"
| filter status = "FAILED"
| sort @timestamp desc
| limit 100
```

**2. 결제 성공률 메트릭 (Custom Metric)**
```java
@Component
public class PaymentMetrics {
    private final MeterRegistry meterRegistry;

    public void recordPaymentSuccess(String pgProvider, BigDecimal amount) {
        meterRegistry.counter("payment.success",
            "pg_provider", pgProvider,
            "environment", System.getenv("SPRING_PROFILES_ACTIVE")
        ).increment();
    }

    public void recordPaymentFailure(String pgProvider, String errorCode) {
        meterRegistry.counter("payment.failure",
            "pg_provider", pgProvider,
            "error_code", errorCode
        ).increment();
    }
}
```

**3. CloudWatch Alarm (결제 실패율 > 5%)**
```bash
aws cloudwatch put-metric-alarm \
  --alarm-name payment-failure-rate-high \
  --alarm-description "Payment failure rate exceeds 5%" \
  --metric-name payment.failure \
  --namespace Custom/Ticketing \
  --statistic Sum \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 5 \
  --comparison-operator GreaterThanThreshold \
  --alarm-actions arn:aws:sns:us-east-1:123456789012:payment-alerts
```

**4. PortOne Dashboard 확인 사항**
- **결제 내역**: 실시간 결제 건수, 금액
- **실패 내역**: 에러 코드별 분류 (카드 한도 초과, 승인 거부 등)
- **정산 내역**: 일별 정산 금액 (T+2일 확인)
- **환불 내역**: 환불 요청 → 승인 완료 상태

#### 2.5.7 롤백 계획 (긴급 상황 시)

**실제 모드 전환 후 심각한 오류 발생 시 (예: 결제 실패율 > 50%)**

**1. 즉시 조치 (0-5분)**
```bash
# ECS Service 이전 Task Definition으로 롤백
aws ecs update-service \
  --cluster ticketing-prod \
  --service payment-service \
  --task-definition payment-service-prod:12  # 이전 버전
  --force-new-deployment
```

**2. 테스트 모드로 긴급 전환 (5-10분)**
- Secrets Manager에서 테스트 MID로 변경 (또는 환경변수 오버라이드)
- ECS Task 재시작
- 고객 공지: "결제 일시 중단, 복구 중"

**3. 원인 분석 및 재전환 (1-24시간)**
- CloudWatch Logs 분석: 에러 패턴 파악
- PortOne 기술지원 문의
- PG사 연동 상태 확인 (MID 활성화 여부)
- 수정 후 Staging 재검증 → Production 재배포

**현재 결론 (2026-01-12):**
- **테스트 모드** 사용