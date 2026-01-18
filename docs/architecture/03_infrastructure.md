# 인프라 및 배포 아키텍처

## 1. 인프라 아키텍처

### 1.1 전체 시스템 아키텍처 다이어그램

```mermaid
graph TB
    subgraph "Client Layer"
        User[사용자<br/>브라우저/모바일]
    end

    subgraph "CDN & Frontend (Vercel)"
        NextJS[Next.js 15+<br/>SSR/CSR]
    end

    subgraph "AWS Cloud (ap-northeast-2)"
        subgraph "Public Subnet"
            ALB[Application<br/>Load Balancer]
        end

        subgraph "Private Subnet"
            Gateway[API Gateway<br/>Spring Cloud Gateway<br/>ECS/EC2]

            subgraph "Microservices"
                User_Svc[User Service<br/>ECS/EC2]
                Event_Svc[Event Service<br/>ECS/EC2]
                Queue_Svc[Queue Service<br/>ECS/EC2]
                Reservation_Svc[Reservation Service<br/>ECS/EC2]
                Payment_Svc[Payment Service<br/>ECS/EC2]
            end

            Kafka[Kafka Broker<br/>EC2 t3.small]

            subgraph "Data Layer"
                RDS[(PostgreSQL 18<br/>RDS db.t3.micro<br/>단일 인스턴스)]
                Redis[(ElastiCache Redis<br/>cache.t2.micro)]
            end
        end
    end

    subgraph "External Services"
        PortOne[PortOne<br/>결제 PG]
        reCAPTCHA[Google reCAPTCHA]
        OAuth2[OAuth2 Provider<br/>카카오/네이버/구글]
    end

    subgraph "Monitoring & Logging"
        CloudWatch[AWS CloudWatch<br/>로그/메트릭/알람]
    end

    User -->|HTTPS| NextJS
    NextJS -->|API Request| ALB
    ALB -->|HTTP| Gateway
    Gateway --> User_Svc
    Gateway --> Event_Svc
    Gateway --> Queue_Svc
    Gateway --> Reservation_Svc
    Gateway --> Payment_Svc

    User_Svc --> RDS
    Event_Svc --> RDS
    Reservation_Svc --> RDS
    Payment_Svc --> RDS

    User_Svc --> Redis
    Event_Svc --> Redis
    Queue_Svc --> Redis
    Reservation_Svc --> Redis

    Reservation_Svc --> Kafka
    Payment_Svc --> Kafka
    Event_Svc --> Kafka

    User_Svc -.외부 API.-> reCAPTCHA
    User_Svc -.외부 API.-> OAuth2
    Payment_Svc -.외부 API.-> PortOne

    Gateway -.로그/메트릭.-> CloudWatch
    User_Svc -.로그/메트릭.-> CloudWatch
    Event_Svc -.로그/메트릭.-> CloudWatch
    Queue_Svc -.로그/메트릭.-> CloudWatch
    Reservation_Svc -.로그/메트릭.-> CloudWatch
    Payment_Svc -.로그/메트릭.-> CloudWatch
```

### 1.2 로컬 개발 환경 (LocalStack 기반)

#### 1.2.1 개요
로컬 개발 환경은 **LocalStack**을 사용하여 AWS 서비스를 에뮬레이션하고, **Docker Compose**로 전체 스택을 통합 관리합니다. 이를 통해 개발 비용을 제로로 유지하면서 AWS와 동일한 환경에서 개발할 수 있습니다.

#### 1.2.2 Docker Compose 구성

**`docker-compose.yml` 구조:**
```yaml
version: '3.8'

services:
  # LocalStack (AWS 에뮬레이션)
  localstack:
    image: localstack/localstack:latest
    ports:
      - "4566:4566"  # AWS API Endpoint
    environment:
      - SERVICES=s3,sqs,sns,secretsmanager
      - DEBUG=1
      - DATA_DIR=/tmp/localstack/data
    volumes:
      - "./localstack-data:/tmp/localstack/data"
      - "/var/run/docker.sock:/var/run/docker.sock"

  # PostgreSQL
  postgres:
    image: postgres:18-alpine
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: ticketing
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./init-db.sql:/docker-entrypoint-initdb.d/init-db.sql

  # Redis
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis-data:/data

  # Kafka (Zookeeper + Broker)
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  # API Gateway (개발 시 실행)
  gateway:
    build: ./gateway
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ticketing
      SPRING_REDIS_HOST: redis
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      - postgres
      - redis
      - kafka

  # User Service (개발 시 실행)
  user-service:
    build: ./user-service
    ports:
      - "8081:8081"
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ticketing
      SPRING_REDIS_HOST: redis
    depends_on:
      - postgres
      - redis

  # ... 기타 서비스 (Event, Queue, Reservation, Payment)

volumes:
  postgres-data:
  redis-data:
```

### 1.3 EC2 배포 전략 (Kafka 및 개발 서버)

#### 1.3.1 배포 방법 비교

**배포 옵션:**

| 방법 | 장점 | 단점 | 적용 환경 | 비용 |
|------|------|------|----------|------|
| **Docker Compose** | 간편한 설정, 빠른 배포, 로컬과 동일 | 단일 호스트 제약, 수동 스케일링 | Dev/Staging | 무료 (EC2 기반) |
| **ECS/Fargate** | 서버리스, 오토스케일링, 컨테이너 오케스트레이션 | 비용 높음, 학습 곡선 | Production | $30-50/월 |
| **Kubernetes (EKS)** | 완전한 오케스트레이션, 멀티클라우드 | 복잡도 높음, 비용 높음 | 미채택 (초기) | $72+/월 |

**선택 기준:**
- **초기 (MVP)**: Docker Compose on EC2 (비용 최소화)
- **성장 단계**: ECS Fargate (관리 편의성)
- **엔터프라이즈**: Kubernetes 검토 (트래픽 > 10,000 TPS)

#### 1.3.2 Docker Compose 배포 (Dev/Staging)

**단일 서비스 재배포 (--no-deps):**
```bash
# Gateway만 재배포 (의존성 재시작 없이)
docker-compose up -d --no-deps --build gateway

# User Service만 재배포
docker-compose up -d --no-deps --build user-service

# Payment Service만 재배포
docker-compose up -d --no-deps --build payment-service
```

**전체 백엔드 서비스 재배포 (인프라 제외):**
```bash
# PostgreSQL, Redis, Kafka는 재시작하지 않음
docker-compose up -d --no-deps --build \
  gateway \
  user-service \
  event-service \
  queue-service \
  reservation-service \
  payment-service
```

**특정 서비스 로그 확인:**
```bash
# 실시간 로그 (특정 서비스)
docker-compose logs -f gateway

# 최근 100줄 로그
docker-compose logs --tail=100 payment-service

# 모든 서비스 로그 (타임스탬프 포함)
docker-compose logs -f -t
```

**선택적 배포 예시 (Kafka만 재시작):**
```bash
# Kafka 브로커만 재시작 (설정 변경 후)
docker-compose restart kafka

# Zookeeper + Kafka 재시작
docker-compose restart zookeeper kafka
```

**헬스체크 및 상태 확인:**
```bash
# 모든 컨테이너 상태 확인
docker-compose ps

# 특정 서비스 헬스체크
curl http://localhost:8080/actuator/health  # Gateway
curl http://localhost:8081/actuator/health  # User Service
curl http://localhost:8082/actuator/health  # Event Service
curl http://localhost:8083/actuator/health  # Queue Service
curl http://localhost:8084/actuator/health  # Reservation Service
curl http://localhost:8085/actuator/health  # Payment Service
```

#### 1.3.3 ECS/Fargate 배포 (Production)

**ECS Task Definition 예시 (Gateway):**
```json
{
  "family": "gateway-task",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "containerDefinitions": [
    {
      "name": "gateway",
      "image": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/gateway:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "prod"
        },
        {
          "name": "SPRING_REDIS_HOST",
          "value": "ticketing.redis.cache.amazonaws.com"
        },
        {
          "name": "SPRING_KAFKA_BOOTSTRAP_SERVERS",
          "value": "kafka-ec2-private-ip:9092"
        }
      ],
      "secrets": [
        {
          "name": "SPRING_DATASOURCE_PASSWORD",
          "valueFrom": "arn:aws:ssm:ap-northeast-2:123456789012:parameter/ticketing/db/password"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/gateway",
          "awslogs-region": "ap-northeast-2",
          "awslogs-stream-prefix": "ecs"
        }
      },
      "healthCheck": {
        "command": ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"],
        "interval": 30,
        "timeout": 5,
        "retries": 3,
        "startPeriod": 60
      }
    }
  ]
}
```

**ECS 서비스 생성 (AWS CLI):**
```bash
# ECS 클러스터 생성
aws ecs create-cluster --cluster-name ticketing-cluster --region ap-northeast-2

# Task Definition 등록
aws ecs register-task-definition \
  --cli-input-json file://gateway-task-definition.json \
  --region ap-northeast-2

# ECS 서비스 생성 (ALB 연동)
aws ecs create-service \
  --cluster ticketing-cluster \
  --service-name gateway-service \
  --task-definition gateway-task \
  --desired-count 2 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-abc123,subnet-def456],securityGroups=[sg-backend],assignPublicIp=DISABLED}" \
  --load-balancers "targetGroupArn=arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/gateway-tg/abc123,containerName=gateway,containerPort=8080" \
  --health-check-grace-period-seconds 60 \
  --region ap-northeast-2
```

**Blue/Green 배포 (ECS + CodeDeploy):**
```bash
# appspec.yml 예시
version: 0.0
Resources:
  - TargetService:
      Type: AWS::ECS::Service
      Properties:
        TaskDefinition: "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/gateway-task:2"
        LoadBalancerInfo:
          ContainerName: "gateway"
          ContainerPort: 8080
        PlatformVersion: "LATEST"

Hooks:
  - BeforeInstall: "LambdaFunctionToValidateBeforeInstall"
  - AfterInstall: "LambdaFunctionToValidateAfterTrafficShift"
  - AfterAllowTestTraffic: "LambdaFunctionToValidateAfterTestTrafficStarts"
  - BeforeAllowTraffic: "LambdaFunctionToValidateBeforeAllowingProductionTraffic"
  - AfterAllowTraffic: "LambdaFunctionToValidateAfterAllowingProductionTraffic"
```

**배포 명령:**
```bash
# CodeDeploy Application 생성
aws deploy create-application \
  --application-name ticketing-app \
  --compute-platform ECS \
  --region ap-northeast-2

# Deployment Group 생성
aws deploy create-deployment-group \
  --application-name ticketing-app \
  --deployment-group-name gateway-deployment-group \
  --service-role-arn arn:aws:iam::123456789012:role/CodeDeployServiceRole \
  --ecs-services clusterName=ticketing-cluster,serviceName=gateway-service \
  --load-balancer-info targetGroupPairInfoList=[{targetGroups=[{name=gateway-tg-blue},{name=gateway-tg-green}],prodTrafficRoute={listenerArns=[arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:listener/app/ticketing-alb/abc/def]}}] \
  --blue-green-deployment-configuration "terminateBlueInstancesOnDeploymentSuccess={action=TERMINATE,terminationWaitTimeInMinutes=5},deploymentReadyOption={actionOnTimeout=CONTINUE_DEPLOYMENT}" \
  --region ap-northeast-2

# 배포 실행
aws deploy create-deployment \
  --application-name ticketing-app \
  --deployment-group-name gateway-deployment-group \
  --revision "revisionType=AppSpecContent,appSpecContent={content=$(cat appspec.yml | base64)}" \
  --region ap-northeast-2
```

#### 1.3.4 Kafka EC2 배포 (자체 구축)

**Kafka 설치 스크립트 (Ubuntu 22.04):**
```bash
#!/bin/bash
# kafka-setup.sh

# Java 설치
sudo apt update
sudo apt install -y openjdk-17-jdk

# Kafka 다운로드 및 설치
cd /opt
sudo wget https://downloads.apache.org/kafka/3.6.1/kafka_2.13-3.6.1.tgz
sudo tar -xzf kafka_2.13-3.6.1.tgz
sudo mv kafka_2.13-3.6.1 kafka
sudo chown -R ubuntu:ubuntu /opt/kafka

# Kafka 설정 파일 수정
cat <<EOF | sudo tee /opt/kafka/config/server.properties
broker.id=1
listeners=PLAINTEXT://0.0.0.0:9092
advertised.listeners=PLAINTEXT://<PRIVATE_IP>:9092
log.dirs=/opt/kafka/kafka-logs
num.partitions=3
default.replication.factor=1
log.retention.hours=72
log.segment.bytes=1073741824
zookeeper.connect=localhost:2181
EOF

# Zookeeper 설정
cat <<EOF | sudo tee /opt/kafka/config/zookeeper.properties
dataDir=/opt/kafka/zookeeper-data
clientPort=2181
maxClientCnxns=0
EOF
```

**systemd 서비스 등록:**
```bash
# Zookeeper Service
cat <<EOF | sudo tee /etc/systemd/system/zookeeper.service
[Unit]
Description=Apache Zookeeper Server
After=network.target

[Service]
Type=simple
User=ubuntu
ExecStart=/opt/kafka/bin/zookeeper-server-start.sh /opt/kafka/config/zookeeper.properties
ExecStop=/opt/kafka/bin/zookeeper-server-stop.sh
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Kafka Service
cat <<EOF | sudo tee /etc/systemd/system/kafka.service
[Unit]
Description=Apache Kafka Server
After=network.target zookeeper.service
Requires=zookeeper.service

[Service]
Type=simple
User=ubuntu
ExecStart=/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
ExecStop=/opt/kafka/bin/kafka-server-stop.sh
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# 서비스 활성화 및 시작
sudo systemctl daemon-reload
sudo systemctl enable zookeeper
sudo systemctl enable kafka
sudo systemctl start zookeeper
sleep 10
sudo systemctl start kafka

# 상태 확인
sudo systemctl status zookeeper
sudo systemctl status kafka
```

**Kafka 토픽 생성 스크립트:**
```bash
#!/bin/bash
# create-topics.sh

KAFKA_BIN=/opt/kafka/bin
BOOTSTRAP_SERVER=localhost:9092

# reservation.events 토픽
$KAFKA_BIN/kafka-topics.sh --create \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --topic reservation.events \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=259200000 \
  --config cleanup.policy=delete

# payment.events 토픽
$KAFKA_BIN/kafka-topics.sh --create \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --topic payment.events \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=259200000 \
  --config cleanup.policy=delete

# DLQ 토픽
$KAFKA_BIN/kafka-topics.sh --create \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --topic dlq.reservation \
  --partitions 1 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --config cleanup.policy=delete

$KAFKA_BIN/kafka-topics.sh --create \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --topic dlq.payment \
  --partitions 1 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --config cleanup.policy=delete

# 토픽 목록 확인
$KAFKA_BIN/kafka-topics.sh --list --bootstrap-server $BOOTSTRAP_SERVER

# 토픽 상세 정보
$KAFKA_BIN/kafka-topics.sh --describe \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --topic reservation.events

$KAFKA_BIN/kafka-topics.sh --describe \
  --bootstrap-server $BOOTSTRAP_SERVER \
  --topic payment.events
```

**Kafka 모니터링 명령어:**
```bash
# Consumer Group 상태 확인
/opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group reservation-consumer-group

# Lag 확인
/opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group payment-consumer-group \
  --state

# 메시지 확인 (테스트용)
/opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic payment.events \
  --from-beginning \
  --max-messages 10

# 파티션 정보
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic reservation.events
```

#### 1.3.5 CI/CD 파이프라인 진화

**Phase 1: 수동 배포 (초기)**
```bash
# EC2에 SSH 접속
ssh -i ticketing-key.pem ubuntu@ec2-public-ip

# 코드 업데이트
cd /opt/ticket-queue
git pull origin main

# 서비스 재배포 (Docker Compose)
docker-compose build gateway
docker-compose up -d --no-deps gateway

# 로그 확인
docker-compose logs -f gateway
```

**Phase 2: 반자동 배포 (GitHub Actions → ECR → ECS)**

**`.github/workflows/deploy.yml`:**
```yaml
name: Deploy to ECS

on:
  push:
    branches: [main]
  workflow_dispatch:

env:
  AWS_REGION: ap-northeast-2
  ECR_REPOSITORY: gateway
  ECS_CLUSTER: ticketing-cluster
  ECS_SERVICE: gateway-service
  CONTAINER_NAME: gateway

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v3

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v2
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v1

      - name: Build, tag, and push image to Amazon ECR
        id: build-image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG ./gateway
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          echo "image=$ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG" >> $GITHUB_OUTPUT

      - name: Fill in the new image ID in the Amazon ECS task definition
        id: task-def
        uses: aws-actions/amazon-ecs-render-task-definition@v1
        with:
          task-definition: task-definition.json
          container-name: ${{ env.CONTAINER_NAME }}
          image: ${{ steps.build-image.outputs.image }}

      - name: Deploy Amazon ECS task definition
        uses: aws-actions/amazon-ecs-deploy-task-definition@v1
        with:
          task-definition: ${{ steps.task-def.outputs.task-definition }}
          service: ${{ env.ECS_SERVICE }}
          cluster: ${{ env.ECS_CLUSTER }}
          wait-for-service-stability: true

      - name: Verify deployment
        run: |
          echo "Deployment completed successfully"
          aws ecs describe-services \
            --cluster $ECS_CLUSTER \
            --services $ECS_SERVICE \
            --query 'services[0].deployments' \
            --output table
```

**Phase 3: 완전 자동화 (Blue/Green + Auto Rollback)**

**추가 단계:**
1. **통합 테스트 실행** (Build 후)
2. **Staging 배포** (Green 환경)
3. **Smoke Test** (Green 환경 헬스체크)
4. **Traffic Shifting** (10% → 50% → 100%)
5. **Auto Rollback** (에러율 > 5% 시)

**Rollback 트리거 (CloudWatch Alarm):**
```bash
# 에러율 알람 생성
aws cloudwatch put-metric-alarm \
  --alarm-name gateway-error-rate-high \
  --alarm-description "Gateway 5xx error rate > 5%" \
  --metric-name HTTPCode_Target_5XX_Count \
  --namespace AWS/ApplicationELB \
  --statistic Sum \
  --period 60 \
  --threshold 5 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=TargetGroup,Value=targetgroup/gateway-tg/abc123 \
  --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:ap-northeast-2:123456789012:rollback-trigger \
  --region ap-northeast-2
```

**예상 배포 시간:**
- Phase 1 (수동): 10-15분
- Phase 2 (반자동): 8-10분
- Phase 3 (완전 자동): 5-7분 (Blue/Green), Rollback 1-2분

#### 1.2.3 초기 DB 스키마 생성

**`init-db.sql`:**
```sql
-- 서비스별 스키마 생성
CREATE SCHEMA IF NOT EXISTS user_service;
CREATE SCHEMA IF NOT EXISTS event_service;
CREATE SCHEMA IF NOT EXISTS reservation_service;
CREATE SCHEMA IF NOT EXISTS payment_service;
CREATE SCHEMA IF NOT EXISTS common;

-- 서비스별 전용 사용자 생성 및 권한 부여
CREATE USER user_svc_user WITH PASSWORD 'user_password';
GRANT ALL PRIVILEGES ON SCHEMA user_service TO user_svc_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA user_service TO user_svc_user;

CREATE USER event_svc_user WITH PASSWORD 'event_password';
GRANT ALL PRIVILEGES ON SCHEMA event_service TO event_svc_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA event_service TO event_svc_user;

CREATE USER reservation_svc_user WITH PASSWORD 'reservation_password';
GRANT ALL PRIVILEGES ON SCHEMA reservation_service TO reservation_svc_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA reservation_service TO reservation_svc_user;

CREATE USER payment_svc_user WITH PASSWORD 'payment_password';
GRANT ALL PRIVILEGES ON SCHEMA payment_service TO payment_svc_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA payment_service TO payment_svc_user;

-- 공통 Outbox 테이블
CREATE TABLE IF NOT EXISTS common.outbox_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_type VARCHAR(255) NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(255) NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  published BOOLEAN NOT NULL DEFAULT FALSE,
  published_at TIMESTAMP
);

CREATE INDEX idx_outbox_published ON common.outbox_events(published, created_at);
```

#### 1.2.4 로컬 → AWS 전환 전략

**전환 최소화 방안:**
1. **환경 변수 기반 설정**:
   - `application-local.yml` (로컬)
   - `application-prod.yml` (AWS)
   - 엔드포인트만 환경변수로 변경 (S3, SQS, SNS 등)

2. **AWS SDK Profile 활용**:
   ```java
   // LocalStack: http://localhost:4566
   // AWS: 기본 엔드포인트 (환경변수로 제어)
   AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
       .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(
           System.getenv("AWS_ENDPOINT_URL"),  // LocalStack: http://localhost:4566
           "ap-northeast-2"
       ))
       .build();
   ```

3. **Testcontainers 활용** (통합 테스트):
   ```java
   @Testcontainers
   class IntegrationTest {
       @Container
       static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

       @Container
       static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
           .withExposedPorts(6379);
   }
   ```

### 1.4 프론트엔드 인프라

#### 1.4.1 Next.js 애플리케이션 구조

**기술 스택:**
- Next.js 15+ (App Router)
- React 19+
- TypeScript
- Tailwind CSS
- Zustand (상태 관리)

**프로젝트 구조:**
```
ticket-queue/frontend/
├── app/
│   ├── (auth)/
│   │   ├── login/page.tsx
│   │   ├── register/page.tsx
│   ├── (events)/
│   │   ├── events/page.tsx
│   │   ├── events/[id]/page.tsx
│   ├── (queue)/
│   │   ├── queue/[eventId]/page.tsx
│   ├── (reservation)/
│   │   ├── reservations/page.tsx
│   │   ├── reservations/[id]/page.tsx
│   ├── layout.tsx
│   ├── page.tsx
├── components/
│   ├── auth/
│   ├── events/
│   ├── queue/
│   ├── reservation/
├── lib/
│   ├── api/
│   │   ├── auth.ts
│   │   ├── events.ts
│   │   ├── queue.ts
│   │   ├── reservations.ts
│   ├── store/
│   ├── utils/
├── public/
├── next.config.js
├── package.json
```

#### 1.4.2 Vercel 배포 설정

**`vercel.json`:**
```json
{
  "buildCommand": "npm run build",
  "outputDirectory": ".next",
  "framework": "nextjs",
  "regions": ["icn1"],
  "env": {
    "NEXT_PUBLIC_API_URL": "https://api.ticketing.example.com"
  }
}
```

**환경 변수 관리:**
| 환경 | API Gateway URL | 비고 |
|------|-----------------|------|
| Local | `http://localhost:8080` | Docker Compose |
| Staging | `https://api-staging.ticketing.example.com` | 선택 사항 |
| Production | `https://api.ticketing.example.com` | ALB 엔드포인트 |

**Vercel 배포 프로세스:**
1. GitHub 저장소 연동
2. main 브랜치 push 시 자동 배포
3. Preview 배포: PR 생성 시 자동 프리뷰 환경 생성
4. 환경 변수는 Vercel 대시보드에서 관리

#### 1.4.3 API Gateway 연동 설정

**API Client 설정 (`lib/api/client.ts`):**
```typescript
import axios from 'axios';

const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// JWT 토큰 자동 추가
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 401 응답 시 토큰 갱신
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      // Token refresh 로직
      const refreshToken = localStorage.getItem('refreshToken');
      if (refreshToken) {
        const { data } = await axios.post(`${process.env.NEXT_PUBLIC_API_URL}/auth/refresh`, {
          refreshToken,
        });
        localStorage.setItem('accessToken', data.accessToken);
        // 원래 요청 재시도
        return apiClient.request(error.config);
      }
    }
    return Promise.reject(error);
  }
);

export default apiClient;
```

### 1.5 AWS 클라우드 인프라 구성 (무료티어/저비용 최적화)

#### 1.5.1 리전 및 가용 영역
- **리전**: `ap-northeast-2` (서울)
- **가용 영역**: 초기 Single-AZ (`ap-northeast-2a`), 향후 Multi-AZ 전환

#### 1.5.2 컴퓨트 (Compute)

**옵션 1: ECS Fargate (권장)**
- **장점**: 서버리스, 오토스케일링 간편, 운영 부담 낮음
- **단점**: 무료티어 없음 (월 약 $30-50)
- **스펙**: 0.5 vCPU, 1GB RAM per task

**옵션 2: EC2 (비용 최적화)**
- **인스턴스 타입**: `t2.micro` (무료티어 750시간/월) 또는 `t3.micro`
- **장점**: 무료티어 활용 가능, 비용 저렴
- **단점**: 서버 관리 필요, 오토스케일링 복잡
- **배포 방안**: 단일 EC2에 모든 서비스 컨테이너 실행 (Docker Compose) 또는 서비스별 EC2 분리

**선택 기준:**
- 초기: EC2 t2.micro로 시작 (무료티어)
- 트래픽 증가 시: ECS Fargate로 마이그레이션

#### 1.5.3 로드 밸런서

**Application Load Balancer (ALB)**
- **무료티어**: 없음 (시간당 $0.0225 + LCU 기준 과금)
- **월 예상 비용**: $16-20
- **대안**: ALB 없이 Route 53 → EC2 직접 연결 (SSL 인증서는 Let's Encrypt)
- **권장**: 초기에는 ALB 사용 (HTTPS, 헬스체크, Path 라우팅)

#### 1.5.4 데이터베이스

**Amazon RDS PostgreSQL**
- **인스턴스**: `db.t3.micro` (무료티어 750시간/월, 20GB 스토리지)
- **버전**: PostgreSQL 18
- **Multi-AZ**: 초기 Single-AZ (무료티어), 향후 Multi-AZ 전환 ($$$)
- **백업**: 자동 백업 1일 보관 (무료), 7일 보관 시 추가 비용
- **모니터링**: Enhanced Monitoring 비활성화 (비용 절감)
- **스토리지**: 범용 SSD (gp3), 20GB (무료티어)

**비용 최적화:**
- Single-AZ 배포
- 백업 보관 기간 최소화 (1일)
- Read Replica 제외 (초기)
- Connection Pooling (서비스별 HikariCP 설정)

#### 1.5.5 캐시 및 메모리 스토어

**Amazon ElastiCache Redis**
- **노드 타입**: `cache.t2.micro` (무료티어 750시간/월) 또는 `cache.t3.micro`
- **버전**: Redis 7.x
- **클러스터 모드**: 비활성화 (단일 노드)
- **Multi-AZ**: 비활성화 (초기)
- **백업**: 자동 백업 비활성화 (비용 절감)

**비용 최적화:**
- 단일 노드 구성
- 백업 비활성화 (Redis 데이터는 휘발성으로 간주)
- Replication 제외 (초기)

#### 1.5.6 메시징

**Kafka on EC2 (자체 구축)**
- **이유**: Amazon MSK는 고비용 (최소 월 $200+)
- **인스턴스**: `t3.small` 1대 (초기), 향후 3대 클러스터 확장
- **월 예상 비용**: $15-20 (t3.small 1대 기준)
- **스토리지**: EBS gp3 100GB

**대안: Amazon SNS/SQS**
- **장점**: 완전 관리형, 무료티어 (SNS: 100만 요청, SQS: 100만 요청)
- **단점**: Kafka의 이벤트 스트리밍 기능 부족, 메시지 순서 보장 어려움
- **결론**: Kafka 유지 권장 (이벤트 스트리밍 필수)

#### 1.5.7 모니터링 및 로깅

**AWS CloudWatch**
- **무료티어**:
  - 로그 수집: 5GB/월
  - 커스텀 메트릭: 10개
  - 알람: 10개
  - API 요청: 100만 건/월
- **전략**: 무료티어 한도 내 운영 (섹션 14에서 상세 설명)

**비용 초과 방지:**
- 로그 보관 기간: 1일
- 핵심 메트릭만 선별 수집 (10개 이내)
- 알람 최소화 (10개 이내)

#### 1.5.8 도메인 및 DNS

**Amazon Route 53**
- **호스팅 영역**: $0.50/월 (1개)
- **DNS 쿼리**: 100만 건당 $0.40
- **헬스 체크**: $0.50/개 (선택 사항)

**비용 최적화:**
- 단일 호스팅 영역
- 헬스 체크 최소화 또는 제외

#### 1.5.9 기타 서비스

**Amazon ECR (컨테이너 레지스트리)**
- **무료티어**: 500MB 스토리지/월
- **전략**: 이미지 최적화 (멀티스테이지 빌드), 불필요한 이미지 삭제

**AWS Secrets Manager vs Parameter Store**
- **Secrets Manager**: $0.40/시크릿/월 (비용 발생)
- **Parameter Store**: 무료 (Standard tier)
- **선택**: Parameter Store 사용 (DB 비밀번호, API 키 등)

### 1.6 비용 최적화 전략

#### 1.6.1 무료티어 활용 체크리스트

| 서비스 | 무료티어 한도 | 초과 시 비용 | 대응 전략 |
|--------|--------------|-------------|----------|
| EC2 | t2.micro 750시간/월 | $0.0116/시간 | 단일 인스턴스 유지 |
| RDS | db.t3.micro 750시간/월, 20GB | $0.017/시간, $0.115/GB | Single-AZ, 백업 최소화 |
| ElastiCache | cache.t2.micro 750시간/월 | $0.017/시간 | 단일 노드 |
| ALB | 없음 | $16-20/월 | 필수 사용 |
| CloudWatch | 로그 5GB, 메트릭 10개, 알람 10개 | $0.50/GB, $0.30/메트릭, $0.10/알람 | 한도 준수 |
| ECR | 500MB | $0.10/GB | 이미지 최적화 |
| Route 53 | 없음 | $0.50/월 | 단일 호스팅 영역 |

**월 예상 비용 (무료티어 최대 활용):**
- EC2 (Kafka): $15-20
- ALB: $16-20
- Route 53: $0.50
- CloudWatch (초과 시): $0-5
- **합계**: $30-45/월

#### 1.6.2 리소스 우선순위

**필수 리소스 (비용 발생 불가피):**
- ALB (HTTPS, Path 라우팅)
- Kafka EC2 (이벤트 스트리밍)
- Route 53 (도메인)

**무료티어 활용 가능:**
- EC2 t2.micro (백엔드 서비스)
- RDS db.t3.micro (데이터베이스)
- ElastiCache cache.t2.micro (Redis)
- CloudWatch (한도 내)

**선택 사항 (제외 또는 최소화):**
- Multi-AZ 배포
- Read Replica
- Enhanced Monitoring
- Auto Scaling (수동 스케일링)
- Staging 환경

#### 1.6.3 Auto Scaling 전략

**초기 설정:**
- 최소 인스턴스: 1개
- 최대 인스턴스: 3개
- Target Tracking: CPU 70%

**비용 절감:**
- 평상시: 1개 인스턴스로 운영
- 티켓 오픈 시: 수동 스케일 아웃 (3개)
- 완전 자동화 시: CloudWatch 알람 기반 스케일링 (비용 주의)

#### 1.6.4 개발/스테이징 환경

**옵션 1: Staging 환경 제외**
- Production 환경만 운영
- 로컬 개발 환경에서 충분한 테스트 진행

**옵션 2: Production 공유**
- 별도 네임스페이스 또는 서브도메인 사용
- 동일 인프라에서 Staging 환경 구성

**옵션 3: 주말 자동 종료**
- AWS Lambda로 주말/야간 Staging 환경 자동 종료
- 월 비용 30% 절감 가능

**권장**: 옵션 1 (Staging 제외), 필요 시 옵션 2

#### 1.6.5 CloudWatch 로그 최적화

**로그 보관 전략:**
- 일반 로그: 1일 보관
- 중요 로그 (에러, 보안): 7일 보관
- 로그 그룹별 필터: DEBUG 로그 제외

**무료 한도 준수:**
- 목표: 5GB/월 이내
- 모니터링: CloudWatch Metrics로 사용량 추적
- 알람: 4GB 도달 시 알림

#### 1.6.6 S3 스토리지 (선택 사항)

**사용 용도:**
- 이미지 업로드 (공연 포스터 등)
- 로그 아카이빙 (CloudWatch → S3)

**비용 최적화:**
- 무료티어: 5GB Standard, 20,000 GET, 2,000 PUT
- Lifecycle Policy: 30일 후 Glacier 이관
- CloudFront 연동 시 추가 비용 발생 (선택 사항)

### 1.7 네트워크 구성 (비용 절감)

#### 1.7.1 VPC 설계

**VPC 구성:**
```
VPC: 10.0.0.0/16 (ticket-queue-vpc)
├── Public Subnet (10.0.1.0/24) - ap-northeast-2a
│   ├── ALB
│   └── NAT Gateway (제외 - 비용 절감)
├── Private Subnet (10.0.10.0/24) - ap-northeast-2a
│   ├── ECS/EC2 (백엔드 서비스)
│   ├── RDS
│   ├── ElastiCache
│   └── Kafka
```

**비용 절감 포인트:**
- **NAT Gateway 제외**: $0.045/시간 + 데이터 전송 비용 (월 $32+)
  - **대안**: Private Subnet 리소스는 VPC Endpoint 또는 Public Subnet 배치
- **Single-AZ**: Multi-AZ 데이터 전송 비용 제거 ($0.01/GB)
- **VPC Peering 최소화**: 필요 시만 구성

#### 1.7.2 NAT Gateway vs Public Subnet 배치 결정

**핵심 질문:** Private Subnet의 백엔드 서비스가 외부 API(PortOne, reCAPTCHA, OAuth2)를 호출하려면 NAT Gateway가 필요한가?

**옵션 1: NAT Gateway (표준 아키텍처)**

**구성:**
```
VPC: 10.0.0.0/16
├── Public Subnet (10.0.1.0/24)
│   ├── ALB
│   └── NAT Gateway ($$$)
├── Private Subnet (10.0.10.0/24)
│   ├── 모든 백엔드 서비스 (Gateway, User, Event, Queue, Reservation, Payment)
│   ├── RDS
│   ├── ElastiCache
│   └── Kafka
```

**장점:**
- 표준 AWS 아키텍처 (베스트 프랙티스)
- Private Subnet 서비스가 인터넷 접근 가능 (Outbound만)
- Public IP 노출 없음 (보안 강화)

**단점:**
- **NAT Gateway 비용**: $0.045/시간 (월 $32.40) + 데이터 전송 비용 ($0.045/GB)
- **예상 월 비용**: $42-52 (최소 구성)
- 무료티어 없음 (즉시 과금)

**총 비용 (NAT Gateway 포함):**
- NAT Gateway: $42-52
- ALB: $16-20
- Kafka EC2: $15-20
- Route 53: $0.50
- **합계: $73-92/월**

**옵션 2: Public Subnet 선택 배치 (비용 최적화) - 채택**

**구성:**
```
VPC: 10.0.0.0/16
├── Public Subnet (10.0.1.0/24)
│   ├── ALB
│   ├── User Service (reCAPTCHA, OAuth2 호출)
│   └── Payment Service (PortOne 호출)
├── Private Subnet (10.0.10.0/24)
│   ├── API Gateway (외부 API 호출 없음)
│   ├── Event Service (외부 API 호출 없음)
│   ├── Queue Service (외부 API 호출 없음)
│   ├── Reservation Service (외부 API 호출 없음)
│   ├── RDS
│   ├── ElastiCache
│   └── Kafka
```

**서비스별 배치 결정:**

| 서비스 | 배치 | 이유 | 외부 API 호출 |
|--------|------|------|--------------|
| **User Service** | Public Subnet | reCAPTCHA, OAuth2 (카카오/네이버/구글) | 필수 |
| **Payment Service** | Public Subnet | PortOne 결제 연동 | 필수 |
| **API Gateway** | Private Subnet | 라우팅만 수행, 외부 API 호출 없음 | 없음 |
| **Event Service** | Private Subnet | DB/Redis/Kafka만 사용 | 없음 |
| **Queue Service** | Private Subnet | Redis 전용 | 없음 |
| **Reservation Service** | Private Subnet | DB/Redis/Kafka만 사용 | 없음 |

**Public Subnet 서비스 보안 조치:**

**1. Security Group (Inbound 제한):**
```
# User Service Security Group (user-service-sg)
Inbound:
  - 8081 from alb-sg (ALB에서만 접근 허용)
Outbound:
  - 5432 to db-sg (PostgreSQL)
  - 6379 to redis-sg (Redis)
  - 443 to 0.0.0.0/0 (reCAPTCHA, OAuth2)
  - 2525 to 0.0.0.0/0 (SMTP, 선택 사항)

# Payment Service Security Group (payment-service-sg)
Inbound:
  - 8085 from alb-sg (ALB에서만 접근 허용)
Outbound:
  - 5432 to db-sg (PostgreSQL)
  - 6379 to redis-sg (Redis)
  - 9092 to kafka-sg (Kafka)
  - 443 to 0.0.0.0/0 (PortOne API)
```

**2. AWS WAF (Web Application Firewall) - 선택 사항:**
```bash
# WAF Rule (IP Rate Limiting)
aws wafv2 create-web-acl \
  --name ticketing-waf \
  --scope REGIONAL \
  --default-action Allow={} \
  --rules file://waf-rules.json \
  --region ap-northeast-2

# waf-rules.json (예시)
[
  {
    "Name": "RateLimitRule",
    "Priority": 1,
    "Statement": {
      "RateBasedStatement": {
        "Limit": 2000,
        "AggregateKeyType": "IP"
      }
    },
    "Action": { "Block": {} },
    "VisibilityConfig": {
      "SampledRequestsEnabled": true,
      "CloudWatchMetricsEnabled": true,
      "MetricName": "RateLimitRule"
    }
  },
  {
    "Name": "GeoBlockRule",
    "Priority": 2,
    "Statement": {
      "GeoMatchStatement": {
        "CountryCodes": ["CN", "RU"]
      }
    },
    "Action": { "Block": {} },
    "VisibilityConfig": {
      "SampledRequestsEnabled": true,
      "CloudWatchMetricsEnabled": true,
      "MetricName": "GeoBlockRule"
    }
  }
]

# ALB에 WAF 연결
aws wafv2 associate-web-acl \
  --web-acl-arn arn:aws:wafv2:ap-northeast-2:123456789012:regional/webacl/ticketing-waf/abc123 \
  --resource-arn arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/ticketing-alb/abc123 \
  --region ap-northeast-2
```

**WAF 비용:**
- Web ACL: $5/월
- Rules: $1/rule/월 (2개 = $2)
- Requests: $0.60/million (초기 무시 가능)
- **합계: $7-10/월** (선택 사항)

**3. Application-Level Rate Limiting (Resilience4j):**
```java
// User Service - RateLimiter Configuration
@Configuration
public class RateLimitConfig {
    @Bean
    public RateLimiter reCaptchaRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(100)  // 100 requests
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofSeconds(1))
            .build();
        return RateLimiter.of("reCaptcha", config);
    }
}

// Payment Service - PortOne API Rate Limiter
@Bean
public RateLimiter portoneRateLimiter() {
    RateLimiterConfig config = RateLimiterConfig.custom()
        .limitForPeriod(200)  // PortOne API limit
        .limitRefreshPeriod(Duration.ofMinutes(1))
        .timeoutDuration(Duration.ofSeconds(5))
        .build();
    return RateLimiter.of("portone", config);
}
```

**4. CloudWatch Logs (모든 외부 API 호출 기록):**
```java
// User Service - OAuth2 호출 로깅
@Slf4j
@Component
public class OAuth2ClientLogger {
    @Around("execution(* com.ticketing.user.client.OAuth2Client.*(..))")
    public Object logOAuth2Call(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().getName();
        long start = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;

            log.info("OAuth2 API call success: method={}, duration={}ms", method, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("OAuth2 API call failed: method={}, duration={}ms, error={}",
                method, duration, e.getMessage(), e);
            throw e;
        }
    }
}

// Payment Service - PortOne 호출 로깅
@Slf4j
@Component
public class PortOneClientLogger {
    @Around("execution(* com.ticketing.payment.client.PortOneClient.*(..))")
    public Object logPortOneCall(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();
        long start = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;

            log.info("PortOne API call success: method={}, duration={}ms", method, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("PortOne API call failed: method={}, duration={}ms, args={}, error={}",
                method, duration, args, e.getMessage(), e);
            throw e;
        }
    }
}
```

**5. VPC Flow Logs (네트워크 감사):**
```bash
# VPC Flow Logs 활성화 (S3 저장, 비용 최소화)
aws ec2 create-flow-logs \
  --resource-type VPC \
  --resource-ids vpc-abc123 \
  --traffic-type ALL \
  --log-destination-type s3 \
  --log-destination arn:aws:s3:::ticketing-flow-logs \
  --max-aggregation-interval 600 \
  --region ap-northeast-2

# Flow Logs 분석 (Athena 쿼리 예시)
CREATE EXTERNAL TABLE vpc_flow_logs (
  account_id STRING,
  interface_id STRING,
  srcaddr STRING,
  dstaddr STRING,
  srcport INT,
  dstport INT,
  protocol INT,
  packets BIGINT,
  bytes BIGINT,
  start BIGINT,
  end BIGINT,
  action STRING,
  log_status STRING
)
PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED
FIELDS TERMINATED BY ' '
LOCATION 's3://ticketing-flow-logs/AWSLogs/123456789012/vpcflowlogs/ap-northeast-2/';

-- 외부 API 호출 추적 (HTTPS: 443 포트)
SELECT srcaddr, dstaddr, COUNT(*) as request_count, SUM(bytes) as total_bytes
FROM vpc_flow_logs
WHERE dstport = 443 AND action = 'ACCEPT'
GROUP BY srcaddr, dstaddr
ORDER BY request_count DESC
LIMIT 100;
```

**비용 비교 (월 기준):**

| 항목 | NAT Gateway | Public Subnet | 절감액 |
|------|-------------|---------------|--------|
| NAT Gateway | $42-52 | $0 | $42-52 |
| WAF (선택) | - | $7-10 | - |
| VPC Flow Logs (선택) | $0.50-1 | $0.50-1 | $0 |
| **총 비용** | **$42-53** | **$7-11 (선택)** | **$31-42** |

**보안 수준 비교:**

| 항목 | NAT Gateway | Public Subnet (보안 조치 적용) |
|------|-------------|-------------------------------|
| Public IP 노출 | 없음 | 있음 (ALB 뒤) |
| Inbound 공격 노출 | 낮음 | 중간 (Security Group으로 완화) |
| Outbound 제어 | 가능 | 가능 (Security Group) |
| DDoS 보호 | AWS Shield Standard | AWS Shield Standard + WAF (선택) |
| 네트워크 감사 | VPC Flow Logs | VPC Flow Logs |
| **종합 평가** | **높음** | **중상 (조치 후)** |

**결정 사항 (채택): Public Subnet (선택 배치)**

**채택 이유:**
1. **비용 절감**: NAT Gateway $42-52/월 제거 (MVP 단계 필수)
2. **충분한 보안**: Security Group + ALB + Rate Limiting으로 기본 보안 확보
3. **선택적 강화**: WAF 추가 시 엔터프라이즈급 보안 가능
4. **성능**: NAT Gateway Hop 제거로 지연 시간 감소 (외부 API 호출)

**전환 조건 (NAT Gateway 도입 고려):**
1. **트래픽 증가**: 외부 API 호출 > 1M requests/월 (데이터 전송 비용 고려)
2. **보안 감사**: PCI-DSS, ISO 27001 등 컴플라이언스 요구 시
3. **비용 여유**: 월 예산 > $100 (NAT Gateway 비용 감당 가능)
4. **보안 사고**: Public Subnet 서비스 대상 공격 발생 시

**모니터링 항목 (Public Subnet 서비스):**
```yaml
CloudWatch Alarms:
  - User Service 외부 API 실패율 > 5%
  - Payment Service PortOne 호출 실패율 > 5%
  - User Service CPU > 80% (DDoS 징후)
  - Payment Service 비정상 트래픽 급증 (> 1000 req/min)
  - Security Group 규칙 위반 로그

CloudWatch Logs Insights 쿼리:
  # OAuth2 실패율
  fields @timestamp, @message
  | filter @message like /OAuth2 API call failed/
  | stats count() as failure_count by bin(5m)

  # PortOne API 지연 시간
  fields @timestamp, duration
  | filter @message like /PortOne API call/
  | stats avg(duration), max(duration), p95(duration) by bin(5m)
```

**장기 계획:**
- **Phase 1 (0-6개월)**: Public Subnet 배치, 비용 절감 우선
- **Phase 2 (6-12개월)**: 트래픽 증가 시 NAT Gateway 도입 검토
- **Phase 3 (12개월+)**: Multi-AZ NAT Gateway, VPC Endpoint (S3, DynamoDB) 추가

#### 1.7.3 Security Group 규칙

**ALB Security Group (alb-sg):**
```
Inbound:
  - 80 (HTTP) from 0.0.0.0/0
  - 443 (HTTPS) from 0.0.0.0/0
Outbound:
  - 8080 to backend-sg (Gateway)
```

**Backend Services Security Group (backend-sg):**
```
Inbound:
  - 8080-8085 from alb-sg
Outbound:
  - 5432 to db-sg (PostgreSQL)
  - 6379 to redis-sg (Redis)
  - 9092 to kafka-sg (Kafka)
  - 443 to 0.0.0.0/0 (외부 API: PortOne, reCAPTCHA)
```

**Database Security Group (db-sg):**
```
Inbound:
  - 5432 from backend-sg
Outbound:
  - 모두 차단
```

**Redis Security Group (redis-sg):**
```
Inbound:
  - 6379 from backend-sg
Outbound:
  - 모두 차단
```

**Kafka Security Group (kafka-sg):**
```
Inbound:
  - 9092 from backend-sg
  - 2181 from backend-sg (Zookeeper)
Outbound:
  - 모두 허용 (클러스터 내부 통신)
```

#### 1.7.4 데이터 전송 비용 최소화

**동일 AZ 내 배치:**
- 모든 리소스를 `ap-northeast-2a` 단일 AZ에 배치
- AZ 간 데이터 전송 비용 제거 ($0.01/GB)

**외부 API 호출 최소화:**
- PortOne, reCAPTCHA 호출 횟수 최소화
- API 응답 캐싱 (가능한 경우)
- 데이터 전송량 압축

**CloudFront 제외 (초기):**
- Vercel CDN으로 정적 리소스 제공
- API는 ALB 직접 연결
- 필요 시 CloudFront 추가 ($0.085/GB)

### 1.8 고가용성 및 확장성 전략 (단계적 적용)

#### 1.8.1 초기 아키텍처 (Single-AZ)

**목표:** 비용 최소화, 기본 가용성 확보

**구성:**
- EC2/ECS: Single-AZ, 1-2 인스턴스
- RDS: Single-AZ, 자동 백업 1일
- ElastiCache: 단일 노드
- Kafka: 단일 브로커

**예상 가용성:** 99.5% (AWS SLA 기준)

**제약사항:**
- AZ 장애 시 서비스 중단
- RDS 장애 시 복구 시간 발생 (Snapshot 기반)
- Redis 장애 시 캐시 손실 (서비스 지속 가능)

#### 1.8.2 성장 단계 (Multi-AZ)

**전환 시점:**
- 월간 활성 사용자 10,000명 이상
- 티켓 오픈 시 동시 접속 100,000명 이상
- 비용 여유 발생 (월 $100+ 가능)

**구성:**
```
VPC: 10.0.0.0/16
├── Public Subnet A (10.0.1.0/24) - ap-northeast-2a
│   └── ALB (Multi-AZ)
├── Public Subnet B (10.0.2.0/24) - ap-northeast-2b
│   └── ALB (Multi-AZ)
├── Private Subnet A (10.0.10.0/24) - ap-northeast-2a
│   ├── ECS/EC2 (백엔드)
│   ├── RDS Primary
│   ├── ElastiCache Primary
│   └── Kafka Broker 1
├── Private Subnet B (10.0.20.0/24) - ap-northeast-2b
│   ├── ECS/EC2 (백엔드)
│   ├── RDS Standby
│   ├── ElastiCache Replica
│   └── Kafka Broker 2
```

**개선 사항:**
- RDS Multi-AZ: 자동 페일오버 (1-2분)
- ElastiCache Replication: 읽기 성능 향상, 장애 대응
- Kafka 3 브로커: Replication Factor 2-3
- ECS/EC2 Auto Scaling: 최소 2, 최대 10

**예상 가용성:** 99.95%

**추가 비용:**
- RDS Multi-AZ: +100% ($34/월)
- ElastiCache Replica: +100% ($24/월)
- Kafka 2대 추가: +$30/월
- NAT Gateway (옵션): +$32/월
- **합계**: +$120-150/월

#### 1.8.3 Auto Scaling 정책

**Target Tracking Scaling:**
```yaml
Target:
  - CPU Utilization: 70%
  - Memory Utilization: 80%
  - ALB Request Count: 1000 req/min per instance

Scale Out:
  - Cooldown: 60초
  - Increment: +1 인스턴스

Scale In:
  - Cooldown: 300초
  - Decrement: -1 인스턴스
```

**Step Scaling (선택 사항):**
```yaml
CPU > 80%:
  - +2 인스턴스 (즉시)
CPU > 90%:
  - +3 인스턴스 (즉시)
CPU < 40%:
  - -1 인스턴스 (5분 후)
```

#### 1.8.4 장애 복구 (Disaster Recovery) 계획

**백업 전략:**
- **RDS**: 자동 백업 1일 보관 (무료), 수동 스냅샷 7일 보관
- **Redis**: 데이터 휘발성, 백업 제외
- **Kafka**: 메시지 보관 기간 3일 (디스크 용량에 따라 조정)

**복구 시나리오:**

| 장애 유형 | RTO (복구 시간) | RPO (데이터 손실) | 복구 절차 |
|----------|----------------|------------------|----------|
| EC2 인스턴스 다운 | 5-10분 | 없음 | Auto Scaling 또는 수동 재시작 |
| RDS Single-AZ 장애 | 30-60분 | 최대 5분 | Snapshot에서 복구 |
| RDS Multi-AZ 장애 | 1-2분 | 없음 | 자동 페일오버 |
| Redis 장애 | 5분 | 캐시 손실 | 재시작, 캐시 재구축 |
| Kafka 브로커 다운 | 1분 | 없음 (Replication) | 자동 Leader 재선출 |
| AZ 전체 장애 | 60-120분 | 최대 5분 | Multi-AZ 전환 후 자동 복구 |

**초기 단계 (Single-AZ) 주의사항:**
- AZ 장애 시 서비스 중단 불가피
- 백업에서 복구 시 다운타임 발생
- **대응**: CloudWatch 알람으로 즉시 감지, 수동 복구

#### 1.8.5 확장성 로드맵

**Phase 1 (현재): 비용 최소화**
- Single-AZ, 최소 리소스
- 수동 스케일링
- 목표 TPS: 100-500

**Phase 2 (6개월): 기본 고가용성**
- Multi-AZ 전환
- Auto Scaling 활성화
- 목표 TPS: 500-2000

**Phase 3 (1년): 성능 최적화**
- Redis Cluster 모드
- Kafka 클러스터 확장 (5 브로커)
- Read Replica 추가
- 목표 TPS: 2000-10000

**Phase 4 (2년): 엔터프라이즈**
- Multi-Region 배포
- CDN 최적화
- Serverless 전환 검토
- 목표 TPS: 10000+

---

## 2. 배포 및 CI/CD

### 2.1 배포 전략

**초기:** Rolling Update (비용 효율적)
**성장 단계:** Blue-Green 배포

### 2.2 CI/CD 파이프라인

**GitHub Actions 워크플로우:**
1. 트리거: `main` 브랜치 push
2. 빌드: Maven/Gradle 빌드, 테스트 실행
3. 도커 이미지: 멀티스테이지 빌드로 최적화
4. ECR 푸시: AWS ECR에 이미지 업로드
5. ECS 배포: ECS Task Definition 업데이트, 서비스 재배포

**무료티어:** GitHub Actions 2000분/월

### 2.3 로컬 개발 환경

**Docker Compose:** 전체 스택 (PostgreSQL, Redis, Kafka, 서비스)
**Hot Reload:** Spring DevTools
**LocalStack:** AWS 서비스 에뮬레이션

### 2.4 Graceful Shutdown

**설정:**
```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
server:
  shutdown: graceful
```

**프로세스:**
1. ALB 헬스체크 실패 응답
2. 신규 요청 거부
3. 진행 중 요청 완료 대기 (최대 30초)
4. 종료

**관련 요구사항:** REQ-GW-026
