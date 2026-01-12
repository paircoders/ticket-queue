# 운영 및 유지보수

## 1. 모니터링 및 로깅

### 1.1 CloudWatch 통합

**로그 수집:** 5GB/월 무료티어 활용
**로그 보관:** 1일 (중요 로그만 7일)
**JSON 구조화:** 파싱 용이

### 1.2 CloudWatch 메트릭 (무료티어 10개 이내)

**선정 기준:** 운영 필수 메트릭 + 알람 트리거 최소 세트

| # | 메트릭 | 설명 | 알람 임계값 |
|---|--------|------|-------------|
| 1 | API Gateway 요청 수 | 전체 라우트 합산 | > 10,000/min |
| 2 | API Gateway 에러율 | 5xx 에러 비율 | > 5% |
| 3 | Queue 승인률 | 대기열 승인 속도 (명/분) | < 5/min |
| 4 | Reservation 성공률 | 좌석 선점 성공률 | < 70% |
| 5 | Payment 성공률 | 결제 성공률 | < 80% |
| 6 | RDS CPU 사용률 | PostgreSQL CPU | > 80% |
| 7 | Redis 메모리 사용률 | ElastiCache 메모리 | > 90% |
| 8 | Kafka Consumer Lag | 이벤트 처리 지연 | > 1000 messages |
| 9 | DLQ 메시지 수 | Dead Letter Queue | > 10 messages |
| 10 | Circuit Breaker Open | Payment 서킷 오픈 횟수 | > 5/min |

#### 1.2.1 Kafka Consumer Lag 모니터링 상세

**Lag 메트릭 구성:**

| 메트릭 | 설명 | 정상 범위 | 경고 임계값 | 위험 임계값 |
|--------|------|----------|------------|------------|
| Consumer Lag | 파티션별 미처리 메시지 수 | < 100 | > 1000 | > 5000 |
| Lag Growth Rate | Lag 증가 속도 (메시지/분) | < 10/min | > 100/min | > 500/min |
| Processing Time | 메시지당 평균 처리 시간 | < 50ms | > 200ms | > 1000ms |
| Error Rate | 처리 실패 비율 (%) | < 0.1% | > 1% | > 5% |

**측정 방법:**

**1. Kafka CLI 도구 (수동 조회)**
```bash
# Consumer Group Lag 확인
bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group payment-service-consumer \
  --describe

# 출력 예시:
# GROUP                   TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# payment-service         payment.events  0          1000            1500            500
```

**2. JMX Metrics (Prometheus/Grafana 연동)**
```yaml
# Kafka Consumer JMX Metrics
kafka.consumer:
  type: consumer-fetch-manager-metrics
  client-id: payment-service

# 주요 메트릭:
- records-lag-max: 최대 Lag
- records-consumed-rate: 초당 처리량
- fetch-latency-avg: 평균 Fetch 지연 시간
```

**3. Application-level Custom Metric (권장)**
```java
// Spring Boot + Micrometer 구현
@Component
public class KafkaConsumerLagMonitor {

    private final MeterRegistry meterRegistry;
    private final AdminClient adminClient;

    @Scheduled(fixedRate = 10000) // 10초마다 측정
    public void measureConsumerLag() {
        Map<TopicPartition, OffsetAndMetadata> consumerOffsets =
            adminClient.listConsumerGroupOffsets("payment-service-consumer")
                .partitionsToOffsetAndMetadata().get();

        Map<TopicPartition, Long> endOffsets =
            adminClient.listOffsets(
                consumerOffsets.keySet().stream()
                    .collect(Collectors.toMap(
                        tp -> tp,
                        tp -> OffsetSpec.latest()
                    ))
            ).all().get();

        consumerOffsets.forEach((topicPartition, offsetAndMetadata) -> {
            long currentOffset = offsetAndMetadata.offset();
            long endOffset = endOffsets.get(topicPartition);
            long lag = endOffset - currentOffset;

            // CloudWatch로 전송
            meterRegistry.gauge("kafka.consumer.lag",
                Tags.of(
                    "topic", topicPartition.topic(),
                    "partition", String.valueOf(topicPartition.partition()),
                    "group", "payment-service-consumer"
                ),
                lag
            );
        });
    }
}
```

**CloudWatch Alarm 설정 (AWS CLI):**
```bash
# Lag > 1000 경고
aws cloudwatch put-metric-alarm \
  --alarm-name "Kafka-Consumer-Lag-Warning" \
  --alarm-description "Payment Service Consumer Lag > 1000" \
  --metric-name kafka.consumer.lag \
  --namespace TicketQueue \
  --statistic Maximum \
  --period 60 \
  --evaluation-periods 2 \
  --threshold 1000 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=topic,Value=payment.events Name=group,Value=payment-service-consumer \
  --alarm-actions arn:aws:sns:ap-northeast-2:ACCOUNT_ID:DevOps-Alerts

# Lag > 5000 위험 (즉시 대응)
aws cloudwatch put-metric-alarm \
  --alarm-name "Kafka-Consumer-Lag-Critical" \
  --alarm-description "Payment Service Consumer Lag > 5000 - Immediate Action Required" \
  --metric-name kafka.consumer.lag \
  --namespace TicketQueue \
  --statistic Maximum \
  --period 60 \
  --evaluation-periods 1 \
  --threshold 5000 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=topic,Value=payment.events Name=group,Value=payment-service-consumer \
  --alarm-actions arn:aws:sns:ap-northeast-2:ACCOUNT_ID:DevOps-Critical
```

**장애 대응 프로세스:**

**Lag > 1000 (경고):**
1. **원인 조사**: CloudWatch Logs에서 Consumer 에러 로그 확인
2. **처리 시간 확인**: 평균 처리 시간이 증가했는지 체크 (DB 쿼리, 외부 API 타임아웃)
3. **수평 확장 검토**: Consumer 인스턴스 추가 (최대 파티션 수까지)
4. **Lag Growth Rate 모니터링**: 증가 추세면 즉시 스케일 아웃, 감소 추세면 관찰 지속

**Lag > 5000 (위험):**
1. **즉시 스케일 아웃**: Consumer 인스턴스 2배 증가 (ECS Task Count 또는 EC2 Auto Scaling)
2. **고객 공지**: 예매 확정 지연 가능성 안내 (예상 복구 시간 명시)
3. **DLQ 확인**: Dead Letter Queue에 메시지가 누적되고 있는지 체크
4. **임시 조치**: 필요 시 Consumer 병렬 처리 증가 (Spring Kafka `concurrency` 설정 조정)

**DLQ 모니터링 연계:**
- Lag이 계속 증가하면서 DLQ 메시지 수도 증가 → **Consumer 로직 버그 가능성**
- Lag은 증가하지만 DLQ는 정상 → **처리 속도 부족 (스케일 아웃 필요)**
- Lag은 정상이지만 DLQ 급증 → **이벤트 스키마 변경 또는 데이터 오류**

**관련 요구사항:** REQ-PAY-004 (At-least-once 전달), REQ-PAY-010 (멱등성)

**제거된 메트릭:**
- API Gateway 응답 시간 (로그 기반 분석으로 대체)
- RDS 메모리 (Managed RDS 자동 관리)
- Redis Hit Rate (필요 시 Redis INFO 명령으로 확인)
- Queue 대기 인원 (Redis CLI로 수동 조회)

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

| 항목 | 목표 | 측정 방법 | 측정 도구 | 알람 임계값 |
|------|------|----------|----------|-----------|
| 가용성 | 99.5% (초기), 99.9% (Multi-AZ) | Route 53 Health Check (30초 간격) | CloudWatch `HealthCheckStatus` | < 1 (Unhealthy) |
| 응답 시간 (P95) | 대기열 진입 < 100ms, 조회 < 50ms | ALB Target Response Time (자동 수집) | CloudWatch `TargetResponseTime` (p95) | > 100ms / > 50ms |
| 처리량 | 36,000명/시간 (대기열 승인) | Custom Metric (1분 집계) | Micrometer → CloudWatch | < 500/min (정상 600/min) |
| Redis 가용성 | 99.9% | ElastiCache Metrics | CloudWatch ElastiCache | CPU > 80% OR 메모리 > 90% |

**측정 방법 상세:**

#### 1. 가용성 측정 (Route 53 Health Check)

**Health Check 설정 (Terraform 예시):**
```hcl
resource "aws_route53_health_check" "api_gateway" {
  type              = "HTTPS"
  resource_path     = "/health"
  fqdn              = "api.ticket-queue.com"
  port              = 443
  request_interval  = 30  # 30초마다 체크
  failure_threshold = 2   # 2회 연속 실패 시 Unhealthy
  measure_latency   = true

  tags = {
    Name = "API-Gateway-Health-Check"
  }
}

resource "aws_cloudwatch_metric_alarm" "health_check_alarm" {
  alarm_name          = "API-Gateway-Unhealthy"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = "1"
  metric_name         = "HealthCheckStatus"
  namespace           = "AWS/Route53"
  period              = "60"
  statistic           = "Minimum"
  threshold           = "1"
  alarm_description   = "API Gateway Health Check Failed"
  alarm_actions       = [aws_sns_topic.devops_alerts.arn]

  dimensions = {
    HealthCheckId = aws_route53_health_check.api_gateway.id
  }
}
```

**Health Endpoint 구현 (Spring Boot):**
```java
@RestController
@RequestMapping("/health")
public class HealthCheckController {

    private final DataSource dataSource;
    private final RedisTemplate<String, String> redisTemplate;

    @GetMapping
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> health = new HashMap<>();

        try {
            // DB 연결 확인 (1초 타임아웃)
            dataSource.getConnection().isValid(1);
            health.put("database", "UP");
        } catch (Exception e) {
            health.put("database", "DOWN");
            return ResponseEntity.status(503).body(health);
        }

        try {
            // Redis 연결 확인
            redisTemplate.opsForValue().get("health:check");
            health.put("redis", "UP");
        } catch (Exception e) {
            health.put("redis", "DOWN");
            return ResponseEntity.status(503).body(health);
        }

        health.put("status", "UP");
        return ResponseEntity.ok(health);
    }
}
```

**가용성 계산 공식:**
```
가용성 (%) = (총 측정 횟수 - Unhealthy 횟수) / 총 측정 횟수 × 100

예시 (30일 기준):
- 측정 간격: 30초
- 총 측정 횟수: 30일 × 24시간 × 60분 × 2회 = 86,400회
- 99.5% 목표: 최대 432회 실패 허용 (약 3.6시간 다운타임)
- 99.9% 목표: 최대 86회 실패 허용 (약 43분 다운타임)
```

#### 2. 응답 시간 측정 (ALB Target Response Time)

**자동 수집:** ALB는 모든 요청의 Target Response Time을 자동으로 CloudWatch에 전송
**메트릭 이름:** `TargetResponseTime` (Namespace: `AWS/ApplicationELB`)

**P95 계산 (CloudWatch Console):**
```
메트릭: TargetResponseTime
통계: p95
기간: 5분
차원: LoadBalancer, TargetGroup
```

**알람 설정 (AWS CLI):**
```bash
# 대기열 진입 (P95 < 100ms)
aws cloudwatch put-metric-alarm \
  --alarm-name "Queue-Entry-Latency-High" \
  --metric-name TargetResponseTime \
  --namespace AWS/ApplicationELB \
  --statistic Average \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 0.1 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=LoadBalancer,Value=app/ticket-queue-alb/xxx \
               Name=TargetGroup,Value=targetgroup/queue-service/xxx \
  --alarm-actions arn:aws:sns:ap-northeast-2:ACCOUNT_ID:DevOps-Alerts

# 대기열 조회 (P95 < 50ms)
aws cloudwatch put-metric-alarm \
  --alarm-name "Queue-Status-Latency-High" \
  --metric-name TargetResponseTime \
  --namespace AWS/ApplicationELB \
  --statistic Average \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 0.05 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=LoadBalancer,Value=app/ticket-queue-alb/xxx \
               Name=TargetGroup,Value=queue-service/xxx \
  --alarm-actions arn:aws:sns:ap-northeast-2:ACCOUNT_ID:DevOps-Alerts
```

**주의:** ALB TargetResponseTime은 Target (서비스)까지의 응답 시간만 측정. 클라이언트-ALB 간 네트워크 지연은 제외됨

#### 3. 처리량 측정 (Custom Metric)

**Micrometer Counter 구현:**
```java
@Service
public class QueueApprovalService {

    private final MeterRegistry meterRegistry;
    private final Counter approvalCounter;

    public QueueApprovalService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.approvalCounter = Counter.builder("queue.approvals")
            .description("Number of users approved from queue")
            .tag("service", "queue-service")
            .register(meterRegistry);
    }

    @Scheduled(fixedRate = 1000) // 1초마다 실행
    public void approveUsers() {
        List<String> approvedUsers = approveNextBatch(10); // 10명 승인
        approvalCounter.increment(approvedUsers.size());
    }
}
```

**CloudWatch로 자동 전송 (application.yml):**
```yaml
management:
  metrics:
    export:
      cloudwatch:
        namespace: TicketQueue
        batch-size: 20
        step: 1m  # 1분마다 집계하여 전송
  endpoints:
    web:
      exposure:
        include: prometheus,health,metrics
```

**처리량 계산:**
```
목표: 36,000명/시간 = 600명/분
알람 임계값: < 500명/분 (목표의 83%, 여유분 고려)

CloudWatch 쿼리:
SELECT SUM(queue.approvals) FROM TicketQueue
WHERE service = 'queue-service'
GROUP BY 1m
```

#### 4. Redis 가용성 측정 (ElastiCache Metrics)

**자동 수집 메트릭:**
- `CPUUtilization`: CPU 사용률 (%)
- `DatabaseMemoryUsagePercentage`: 메모리 사용률 (%)
- `NetworkBytesIn/Out`: 네트워크 처리량
- `CurrConnections`: 현재 연결 수

**알람 설정:**
```bash
# CPU > 80%
aws cloudwatch put-metric-alarm \
  --alarm-name "Redis-CPU-High" \
  --metric-name CPUUtilization \
  --namespace AWS/ElastiCache \
  --statistic Average \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=CacheClusterId,Value=ticket-queue-redis \
  --alarm-actions arn:aws:sns:ap-northeast-2:ACCOUNT_ID:DevOps-Alerts

# 메모리 > 90%
aws cloudwatch put-metric-alarm \
  --alarm-name "Redis-Memory-Critical" \
  --metric-name DatabaseMemoryUsagePercentage \
  --namespace AWS/ElastiCache \
  --statistic Average \
  --period 300 \
  --evaluation-periods 1 \
  --threshold 90 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=CacheClusterId,Value=ticket-queue-redis \
  --alarm-actions arn:aws:sns:ap-northeast-2:ACCOUNT_ID:DevOps-Critical
```

**가용성 계산:**
```
ElastiCache 가용성 = (CloudWatch Uptime + Redis INFO 기반 연결 성공률)

일반적으로 AWS ElastiCache는 99.9% SLA 제공 (Multi-AZ 활성화 시)
측정: CloudWatch `Uptime` 메트릭 (초 단위)
```

**관련 요구사항:** REQ-QUEUE-015, REQ-EVT-004, REQ-EVT-005, REQ-EVT-006

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
