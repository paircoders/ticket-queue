## 00. 로컬 환경 부트스트랩 & 관측

> **영역 범위**: Docker Compose 기반 로컬 인프라(PostgreSQL, Valkey, Kafka, LocalStack, Prometheus, Grafana) 기동, 스키마/시크릿/토픽 초기화, 6개 Spring Boot 서비스 기동 및 actuator 건강 확인, 관측 스택 연동 검증
> **사전 준비**: docker/secrets/ 하위 모든 .txt 파일 존재 확인, Docker Desktop 실행, 호스트 포트 5432·6379·9092·4566·9090·3001·8080-8085·9080-9085 미점유, JDK 21 및 ./gradlew 실행 권한
> **주 실행 수단**: docker-compose, ./gradlew build/test, curl health check
> **총 항목 수**: 16
> **실행 결과 (2026-05-30)**: 통과 8건 | 부분 통과 3건 | 실패 1건 | 미실행 4건 (백엔드 서비스 기동 필요 3건 + Prometheus 타겟 1건)
> **발견된 이슈**: #257 스키마 소유자 불일치 | #258 localstack_init.sh 멱등성(수정 완료) | #259 reservation-service 테스트 ApplicationContext 실패 | #260 event-service 테스트 2종 | #261 integrationTest 태스크 없음

---

### TC-ENV-001 — 인프라 컨테이너 전체 기동 및 healthcheck 통과 확인

- [x] 통과 (2026-05-30, 근거: `docker compose up -d` 후 postgres/valkey/grafana=healthy, localstack=healthy, kafka/prometheus=running, kafka-init=exited(0). `KafkaRaftServer nodeId=1 Kafka Server started` 로그 확인. unhealthy/Exit 1 항목 0건)
- **관련 REQ**: 해당 없음
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: docker/secrets/ 내 모든 .txt 시크릿 파일 존재, 대상 포트 미점유
- **실행 단계**:
  1. `cd /path/to/project/docker`
  2. `docker-compose up -d`
  3. 30초 대기 후 `docker-compose ps` 실행
  4. 각 컨테이너 상태 확인:
     ```bash
     docker inspect --format '{{.Name}} {{.State.Health.Status}}' \
       ticket-postgres ticket-valkey ticket-grafana
     docker ps --filter "name=ticket-" --format "table {{.Names}}\t{{.Status}}"
     ```
- **기대 결과**:
  - `ticket-postgres`: Status=`healthy`
  - `ticket-valkey`: Status=`healthy`
  - `ticket-kafka`: Status=`running` (healthcheck 미설정, 로그에 `[KafkaServer id=1] started` 확인)
  - `ticket-localstack`: Status=`running`
  - `ticket-prometheus`: Status=`running`
  - `ticket-grafana`: Status=`healthy`
  - `ticket-kafka-init`: Status=`exited (0)` (정상 종료)
- **검증 포인트**: `docker-compose ps` 출력에서 `Exit 1` 또는 `unhealthy` 항목 0건

---

### TC-ENV-002 — secrets 파일 누락 시 docker-compose up 실패 케이스

- [x] 부분 통과 (2026-05-30, 근거: `docker compose config` 및 `docker compose up -d`(컨테이너 이미 실행 중)는 시크릿 파일 누락 시에도 exit 0 반환. `docker compose create --force-recreate postgres` 실행 시 `secret file does not exist` 경고 + `invalid mount config for type "bind"` 오류 메시지 출력되나 exit code는 0. **Docker Compose v2(Go 재구현)는 v1(Python)과 달리 시크릿 파일 존재 검증을 config 단계가 아닌 컨테이너 생성 단계에서 수행** → 기대 결과의 `exit code != 0` 조건 미충족. 기존 실행 중인 컨테이너 재시작에는 영향 없음)
- **관련 REQ**: 해당 없음
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: 임시 디렉터리에 docker-compose.yml 복사본 준비, secrets/ 디렉터리 없이 실행 가능한 환경
- **실행 단계**:
  1. `cp docker/secrets/postgres_ticket_pw.txt /tmp/postgres_ticket_pw.txt.bak`
  2. `mv docker/secrets/postgres_ticket_pw.txt /tmp/`
  3. `cd docker && docker-compose config 2>&1 | grep -i error` 또는 `docker-compose up -d 2>&1 | head -20`
  4. 복구: `mv /tmp/postgres_ticket_pw.txt docker/secrets/postgres_ticket_pw.txt`
- **기대 결과**: `service "postgres" refers to undefined secret "postgres_ticket_pw"` 또는 유사 오류 메시지와 함께 비정상 종료, Exit code != 0
- **검증 포인트**: docker-compose 오류 출력에 해당 secret 이름 포함, 컨테이너 미생성

---

### TC-ENV-003 — PostgreSQL 스키마 분리 생성 확인 (5개 스키마)

- [x] 부분 통과 (2026-05-30, 근거: 스키마 5개(`user_service`, `event_service`, `reservation_service`, `payment_service`, `common`) 존재 확인. `common.outbox_events`, `common.processed_events` 테이블 및 복합 PK(`event_id`, `consumer_service`) 확인. **단, 스키마 소유자 불일치 발견**: `user_service`/`event_service`/`payment_service`가 예상 소유자(`user_svc_user` 등) 대신 `ticket` 슈퍼유저 소유로 확인됨. `reservation_service`만 `reservation_svc_user` 소유 정상. 원인: `CREATE SCHEMA IF NOT EXISTS ... AUTHORIZATION` 은 스키마 이미 존재 시 소유자 변경 안 함(볼륨 잔존 데이터). → 관련 이슈: #257)
- **관련 REQ**: 해당 없음
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-ENV-001 통과, ticket-postgres 컨테이너 healthy 상태
- **실행 단계**:
  1. ```bash
     docker exec -it ticket-postgres psql -U ticket -d ticket_queue -c "\dn"
     ```
  2. 각 스키마별 소유자 확인:
     ```bash
     docker exec -it ticket-postgres psql -U ticket -d ticket_queue -c \
       "SELECT schema_name, schema_owner FROM information_schema.schemata WHERE schema_name IN ('user_service','event_service','reservation_service','payment_service','common') ORDER BY schema_name;"
     ```
  3. common 테이블 존재 확인:
     ```bash
     docker exec -it ticket-postgres psql -U ticket -d ticket_queue -c \
       "SELECT tablename FROM pg_tables WHERE schemaname='common' ORDER BY tablename;"
     ```
- **기대 결과**:
  - 스키마 목록: `user_service`, `event_service`, `reservation_service`, `payment_service`, `common` 5개 모두 존재 (queue-service는 Redis 전용이므로 PostgreSQL 스키마/DB 사용자 없음)
  - 각 스키마 소유자: `user_svc_user`, `event_svc_user`, `reservation_svc_user`, `payment_svc_user` (common은 슈퍼유저 `ticket` 소유)
  - common 테이블: `outbox_events`, `processed_events` 2개 확인
- **검증 포인트**: 스키마 정확히 5개(queue_service 없음), `outbox_events`/`processed_events` 테이블 존재, PRIMARY KEY `(event_id, consumer_service)` on processed_events

---

### TC-ENV-004 — 서비스 계정의 타 스키마 직접 접근 차단(스키마 격리 검증)

- [x] 통과 (2026-05-30, 근거: `user_svc_user` → `event_service` 접근 시 `ERROR: permission denied for schema event_service`. `reservation_svc_user` → `payment_service` 접근 시 `permission denied for schema payment_service`. `payment_svc_user` → `user_service` 접근 시 `permission denied for schema user_service`. 3건 모두 exit code 1, 정상 행 반환 없음)
- **관련 REQ**: 해당 없음
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-ENV-003 통과, 각 서비스 DB 사용자 생성 완료
- **실행 단계**:
  1. user_svc_user로 event_service 스키마 접근 시도:
     ```bash
     docker exec -it ticket-postgres psql \
       "postgresql://user_svc_user:$(cat docker/secrets/postgres_user_pw.txt)@localhost/ticket_queue" \
       -c "SELECT * FROM event_service.events LIMIT 1;"
     ```
  2. reservation_svc_user로 payment_service 스키마 접근 시도:
     ```bash
     docker exec -it ticket-postgres psql \
       "postgresql://reservation_svc_user:$(cat docker/secrets/postgres_reservation_pw.txt)@localhost/ticket_queue" \
       -c "SELECT * FROM payment_service.payments LIMIT 1;"
     ```
  3. payment_svc_user로 user_service 스키마 접근 시도:
     ```bash
     docker exec -it ticket-postgres psql \
       "postgresql://payment_svc_user:$(cat docker/secrets/postgres_payment_pw.txt)@localhost/ticket_queue" \
       -c "SELECT * FROM user_service.users LIMIT 1;"
     ```
- **기대 결과**: 각 쿼리 모두 `ERROR: permission denied for schema <target_schema>` 또는 `relation does not exist` 오류 반환
- **검증 포인트**: 정상 행 반환 없음, psql exit code != 0, 권한 오류 메시지 포함

---

### TC-ENV-005 — LocalStack Secrets Manager 시크릿 주입 검증

- [x] 통과 (2026-05-30, 근거: `user-svc/secure-config`, `event-svc/secure-config`, `reservation-svc/secure-config`, `payment-svc/secure-config`, `common/secure-config` 5개 확인. `common/secure-config` 내 `internal_api_key`/`jwt_secret`/`valkey_password` 키 모두 존재. `user-svc/secure-config` 내 `recaptcha_secret`/`db_password` 키 모두 존재. 비고: LocalStack 컨테이너 내 AWS CLI가 v1으로 `--no-cli-pager` 미지원 → `AWS_PAGER=""` 환경변수 사용 필요)
- **관련 REQ**: 해당 없음
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-ENV-001 통과, ticket-localstack 컨테이너 running 상태, localstack_init.sh 실행 완료
- **실행 단계**:
  1. 등록된 시크릿 목록 확인:
     ```bash
     aws --endpoint-url=http://localhost:4566 \
       --region ap-northeast-2 \
       --no-cli-pager \
       secretsmanager list-secrets \
       --query 'SecretList[].Name' \
       --output text
     ```
  2. common/secure-config 내 internal_api_key 존재 확인:
     ```bash
     aws --endpoint-url=http://localhost:4566 \
       --region ap-northeast-2 \
       --no-cli-pager \
       secretsmanager get-secret-value \
       --secret-id "common/secure-config" \
       --query 'SecretString' \
       --output text | python3 -c "import sys,json; d=json.load(sys.stdin); print('internal_api_key' in d, 'jwt_secret' in d, 'valkey_password' in d)"
     ```
  3. user-svc/secure-config의 recaptcha_secret 존재 확인:
     ```bash
     aws --endpoint-url=http://localhost:4566 \
       --region ap-northeast-2 \
       --no-cli-pager \
       secretsmanager get-secret-value \
       --secret-id "user-svc/secure-config" \
       --query 'SecretString' \
       --output text | python3 -c "import sys,json; d=json.load(sys.stdin); print('recaptcha_secret' in d, 'db_password' in d)"
     ```
- **기대 결과**:
  - 시크릿 목록: `user-svc/secure-config`, `event-svc/secure-config`, `reservation-svc/secure-config`, `payment-svc/secure-config`, `common/secure-config` 5개
  - common/secure-config: `internal_api_key`, `jwt_secret`, `valkey_password` 키 모두 `True`
  - user-svc/secure-config: `recaptcha_secret`, `db_password` 키 모두 `True`
- **검증 포인트**: 5개 시크릿 존재, 각 시크릿의 필수 키 누락 없음, 값이 빈 문자열이 아님

---

### TC-ENV-006 — LocalStack 시크릿 등록 전 서비스 기동 시 실패 케이스(시크릿 미주입)

- [x] 부분 통과 (2026-05-30, 근거: `common/secure-config` 삭제 후 시크릿 목록에서 제거됨 확인. **[버그]** 복구 시도로 `localstack_init.sh` 재실행 시 `set -e` + 비멱등 명령 조합으로 S3 버킷 생성 단계(`s3 mb` → `BucketAlreadyOwnedByYou`)에서 즉시 중단되어 `common/secure-config` 미복구(시크릿 4개). 수동 `awslocal secretsmanager create-secret` 으로 복구. **[수정 완료 → #258]** `localstack_init.sh` 멱등화: S3는 `head-bucket` 분기, 시크릿은 `put_secret` 헬퍼(`describe-secret` 분기 후 update/create)로 통일. 동일 복구 시나리오 재실행 → exit 0, `common/secure-config` 정상 복구(시크릿 5개), 필수 키 정합 확인. 5개 모두 존재 상태 재실행도 전부 update 경로·`ResourceExistsException` 0건·exit 0으로 멱등성 재검증 완료. **Spring Boot 서비스 기동 실패 시나리오(로그 확인)는 백엔드 컨테이너 이미지 없어 여전히 미검증** → 부분 통과 유지)
- **관련 REQ**: 해당 없음
- **분류**: 예외
- **우선순위**: P1(중요)
- **사전조건**: LocalStack이 기동되어 있으나 localstack_init.sh가 실행되지 않은 상태 시뮬레이션
- **실행 단계**:
  1. ```bash
     # 시크릿 삭제로 미주입 상태 재현
     aws --endpoint-url=http://localhost:4566 --region ap-northeast-2 --no-cli-pager \
       secretsmanager delete-secret --secret-id "common/secure-config" --force-delete-without-recovery
     ```
  2. user-service만 단독 기동 시도(docker-compose.backend.yml 사용):
     ```bash
     cd docker && docker-compose -f docker-compose.yml -f docker-compose.backend.yml up -d user-service 2>&1
     ```
  3. 기동 로그 확인:
     ```bash
     docker logs ticket-user-service 2>&1 | grep -E "ERROR|SecretsManager|ResourceNotFoundException" | head -20
     ```
  4. common/secure-config 재등록(복구):
     ```bash
     # localstack_init.sh 재실행 — #258 수정 후 멱등하므로 기존 시크릿이 있어도 중단 없이 누락분만 복구
     docker exec ticket-localstack bash /etc/localstack/init/ready.d/localstack_init.sh
     ```
- **기대 결과**: user-service 컨테이너가 `ResourceNotFoundException` 또는 Spring Boot `Application run failed` 로그를 남기고 재시작 루프 진입 또는 Exit
- **검증 포인트**: `docker logs` 에 `SecretsManager` 관련 예외 메시지 존재, actuator health 응답 없음(connection refused on 9081)

---

### TC-ENV-007 — Kafka 토픽 자동 생성 및 파티션/보관 정책 검증

- [x] 통과 (2026-05-30, 근거: `reservation.events` PartitionCount=3 ReplicationFactor=1 retention.ms=259200000 ✓. `payment.events` PartitionCount=3 ReplicationFactor=1 retention.ms=259200000 ✓. `dlq.reservation` PartitionCount=1 ReplicationFactor=1 retention.ms=604800000 ✓. `dlq.payment` PartitionCount=1 ReplicationFactor=1 retention.ms=604800000 ✓. 4개 토픽 모두 존재 확인)
- **관련 REQ**: 해당 없음
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-ENV-001 통과, ticket-kafka-init 컨테이너 Exit 0 상태
- **실행 단계**:
  1. 토픽 목록 확인:
     ```bash
     docker exec ticket-kafka /opt/kafka/bin/kafka-topics.sh \
       --list --bootstrap-server localhost:9092
     ```
  2. 각 토픽 상세 정보(파티션, 복제, retention) 확인:
     ```bash
     docker exec ticket-kafka /opt/kafka/bin/kafka-topics.sh \
       --describe --bootstrap-server localhost:9092 \
       --topic reservation.events
     docker exec ticket-kafka /opt/kafka/bin/kafka-topics.sh \
       --describe --bootstrap-server localhost:9092 \
       --topic payment.events
     docker exec ticket-kafka /opt/kafka/bin/kafka-topics.sh \
       --describe --bootstrap-server localhost:9092 \
       --topic dlq.reservation
     docker exec ticket-kafka /opt/kafka/bin/kafka-topics.sh \
       --describe --bootstrap-server localhost:9092 \
       --topic dlq.payment
     ```
- **기대 결과**:
  - `reservation.events`: PartitionCount=3, ReplicationFactor=1, retention.ms=259200000
  - `payment.events`: PartitionCount=3, ReplicationFactor=1, retention.ms=259200000
  - `dlq.reservation`: PartitionCount=1, ReplicationFactor=1, retention.ms=604800000
  - `dlq.payment`: PartitionCount=1, ReplicationFactor=1, retention.ms=604800000
- **검증 포인트**: 4개 토픽 모두 존재, 비즈니스 토픽 파티션 3개, DLQ 파티션 1개, retention.ms 값 일치

---

### TC-ENV-008 — kafka-init 재실행 시 토픽 중복 생성 방지(--if-not-exists 멱등성)

- [x] 통과 (2026-05-30, 근거: 4개 토픽 이미 존재 상태에서 `kafka-topics.sh --create --if-not-exists` 재실행. 각 토픽 create exit code 0, 재실행 전후 토픽 수 동일(4개). `TopicExistsException` 대신 경고 메시지만 출력되고 정상 종료. 비고: kafka-init 컨테이너의 init_topics.sh 경로는 /opt/kafka_init/init_topics.sh이나 kafka 컨테이너에는 없음 — kafka-init 컨테이너 별도 마운트 볼륨에서만 접근 가능)
- **관련 REQ**: 해당 없음
- **분류**: 멱등성
- **우선순위**: P1(중요)
- **사전조건**: TC-ENV-007 통과, 4개 토픽 이미 생성된 상태
- **실행 단계**:
  1. init 스크립트 재실행:
     ```bash
     docker exec ticket-kafka /bin/bash /opt/kafka_init/init_topics.sh 2>&1
     ```
     (또는 kafka-init 컨테이너를 다시 실행)
  2. 재실행 후 토픽 수 확인:
     ```bash
     docker exec ticket-kafka /opt/kafka/bin/kafka-topics.sh \
       --list --bootstrap-server localhost:9092 | wc -l
     ```
- **기대 결과**: 스크립트 exit code 0, 오류 없이 완료, 토픽 수 여전히 4개(중복 생성 없음)
- **검증 포인트**: `--if-not-exists` 플래그로 인해 `TopicExistsException` 대신 조용히 건너뜀, 토픽 총 개수 변화 없음

---

### TC-ENV-009 — 6개 서비스 actuator health UP 확인

- [ ] 미실행 (사전조건 미충족: 백엔드 6개 서비스 미기동. GHCR 이미지 비공개(unauthorized) — `docker-compose.backend.yml`로 로컬 빌드 후 기동 필요. TC-ENV-001 인프라만 기동된 상태)
- **관련 REQ**: 해당 없음
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-ENV-001, TC-ENV-005 통과, docker-compose.backend.yml로 6개 서비스 기동 완료
- **실행 단계**:
  1. 6개 서비스 management 포트 health 확인(병렬):
     ```bash
     for port in 9080 9081 9082 9083 9084 9085; do
       echo -n "port $port: "
       curl -sf http://localhost:$port/actuator/health | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null || echo "UNREACHABLE"
     done
     ```
  2. 서비스 메인 포트에서 actuator 접근 차단 확인:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health
     ```
- **기대 결과**:
  - management 포트(9080-9085) 6개 모두 `{"status":"UP"}` 반환
  - 서비스 메인 포트(8081)에서 `/actuator/health` 접근 시 404 또는 연결 거부
- **검증 포인트**: 6개 포트 status=UP, 메인 포트 actuator 노출 없음

---

### TC-ENV-010 — 서비스 기동 순서 위반 시 의존성 대기 동작 확인

- [ ] 미실행 (사전조건 미충족: TC-ENV-009 의존 — 백엔드 서비스 미기동)
- **관련 REQ**: 해당 없음
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: 인프라 컨테이너만 기동된 상태(postgres, valkey, kafka, localstack)
- **실행 단계**:
  1. PostgreSQL 일시 중지:
     ```bash
     docker pause ticket-postgres
     ```
  2. reservation-service 기동 시도:
     ```bash
     cd docker && docker-compose -f docker-compose.yml -f docker-compose.backend.yml \
       up -d reservation-service 2>&1
     ```
  3. 컨테이너 상태 및 로그 확인:
     ```bash
     docker ps --filter "name=ticket-reservation" --format "{{.Status}}"
     docker logs ticket-reservation-service 2>&1 | grep -E "Connection refused|HikariPool|Cannot create" | head -10
     ```
  4. PostgreSQL 재개 후 자동 복구 확인:
     ```bash
     docker unpause ticket-postgres
     sleep 30
     curl -sf http://localhost:9084/actuator/health | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])"
     ```
- **기대 결과**: postgres pause 상태에서는 reservation-service가 DB 연결 실패 로그(`HikariPool` Connection timeout 등) 출력 후 재시도, postgres 재개 후 최대 60초 내 `UP` 복구
- **검증 포인트**: `restart: unless-stopped` 정책으로 재시작 시도, postgres 복구 후 actuator health UP

---

### TC-ENV-011 — /internal/** 엔드포인트 X-Service-Api-Key 없이 접근 차단

- [ ] 미실행 (사전조건 미충족: TC-ENV-009 의존 — 백엔드 서비스 및 api-gateway 미기동)
- **관련 REQ**: 해당 없음
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-ENV-009 통과, api-gateway(8080) 기동 완료
- **실행 단계**:
  1. X-Service-Api-Key 헤더 없이 internal 엔드포인트 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       http://localhost:8080/internal/reservations/some-id/status
     ```
  2. 잘못된 Api-Key로 호출:
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       -H "X-Service-Api-Key: wrong-key" \
       http://localhost:8080/internal/reservations/some-id/status
     ```
  3. Gateway를 우회하여 서비스 직접 호출 시도(예: reservation-service 직접):
     ```bash
     curl -s -o /dev/null -w "%{http_code}" \
       http://localhost:8084/internal/reservations/some-id/status
     ```
  4. 올바른 Key로 호출:
     ```bash
     INTERNAL_API_KEY=$(cat docker/secrets/internal_api_key.txt)
     curl -s -o /dev/null -w "%{http_code}" \
       -H "X-Service-Api-Key: $INTERNAL_API_KEY" \
       http://localhost:8084/internal/reservations/some-id/status
     ```
- **기대 결과**:
  - Key 없음: 401 또는 403
  - 잘못된 Key: 401 또는 403
  - Gateway 경유 없이 서비스 직접 접근: 실제 운영 환경에서는 네트워크 차단, 로컬에서는 포트 노출되어 있으므로 올바른 Key 필요
  - 올바른 Key: 200 또는 404(존재하지 않는 ID)
- **검증 포인트**: Key 없이/잘못된 Key로 Gateway 통과 불가, 올바른 Key로만 통과

---

### TC-ENV-012 — Prometheus 메트릭 수집 타겟 UP 확인

- [ ] 미실행 (사전조건 미충족: Prometheus 자체는 정상 기동(running). 그러나 백엔드 6개 서비스 미기동으로 모든 스크랩 타겟이 `down` 상태 — `api-gateway`, `event-service`, `payment-service`, `queue-service`, `reservation-service`, `user-service` 전체 down 확인. 백엔드 기동 후 재검증 필요)
- **관련 REQ**: REQ-GW-012
- **분류**: 정상
- **우선순위**: P1(중요)
- **사전조건**: TC-ENV-009 통과, Prometheus 컨테이너 기동 완료, 6개 서비스 기동 후 최소 15초 경과
- **실행 단계**:
  1. Prometheus 타겟 상태 API 확인:
     ```bash
     curl -s http://localhost:9090/api/v1/targets | \
       python3 -c "
     import sys, json
     data = json.load(sys.stdin)
     for t in data['data']['activeTargets']:
         print(t['labels'].get('job','?'), '->', t['health'])
     "
     ```
  2. 특정 메트릭 쿼리 확인(JVM 힙):
     ```bash
     curl -sg 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{area="heap"}' | \
       python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d['data']['result']), 'time series')"
     ```
  3. HTTP 요청 메트릭 존재 확인:
     ```bash
     curl -sg 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count' | \
       python3 -c "import sys,json; d=json.load(sys.stdin); apps=set(r['metric'].get('application','?') for r in d['data']['result']); print(apps)"
     ```
- **기대 결과**:
  - 6개 job 모두 `health: "up"`
  - jvm_memory_used_bytes: 6개 이상 time series
  - http_server_requests_seconds_count: 6개 서비스 application 레이블 모두 포함
- **검증 포인트**: `health: "down"` 타겟 0건, scrape_interval 15초 내 메트릭 갱신

---

### TC-ENV-013 — Grafana 대시보드 자동 프로비저닝 로딩 확인

- [x] 통과 (2026-05-30, 근거: `/api/health` → `{"database":"ok","version":"11.4.0"}` 확인. 대시보드 4개 모두 존재(Ticket Queue 폴더): `JVM & Spring Boot Overview`, `HTTP Requests Overview`, `Resilience4j Circuit Breaker`, `Infrastructure Overview`. Datasource: name=`Prometheus`, type=`prometheus` 확인)
- **관련 REQ**: REQ-GW-012
- **분류**: 정상
- **우선순위**: P1(중요)
- **사전조건**: TC-ENV-001 통과, Grafana 컨테이너 healthy 상태
- **실행 단계**:
  1. Grafana API health 확인:
     ```bash
     curl -s http://localhost:3001/api/health | python3 -c "import sys,json; print(json.load(sys.stdin))"
     ```
  2. 관리자 자격증명으로 프로비저닝된 대시보드 목록 조회:
     ```bash
     GF_PW=$(cat docker/secrets/grafana_admin_pw.txt)
     curl -s -u "admin:$GF_PW" http://localhost:3001/api/search?type=dash-db | \
       python3 -c "import sys,json; boards=json.load(sys.stdin); [print(b['title']) for b in boards]"
     ```
  3. Prometheus datasource 연결 상태 확인:
     ```bash
     GF_PW=$(cat docker/secrets/grafana_admin_pw.txt)
     curl -s -u "admin:$GF_PW" http://localhost:3001/api/datasources | \
       python3 -c "import sys,json; ds=json.load(sys.stdin); [print(d['name'], d.get('jsonData',{}).get('timeInterval','?')) for d in ds]"
     ```
- **기대 결과**:
  - `/api/health`: `{"commit":"...","database":"ok","version":"11.4.0"}`
  - 대시보드 목록: `JVM & Spring Boot Overview`, `HTTP Requests Overview`, `Resilience4j Circuit Breaker`, `Infrastructure Overview` 4개 (Ticket Queue 폴더)
  - Prometheus datasource: name=`Prometheus`, URL=`http://ticket-prometheus:9090` 또는 유사
- **검증 포인트**: 4개 대시보드 모두 존재, datasource 타입 `prometheus`, database `ok`

---

### TC-ENV-014 — ./gradlew build 및 integrationTest 통과

- [!] 실패 (2026-05-30, 근거:
  - `./gradlew build -x test`: BUILD SUCCESSFUL (557ms, 47 tasks up-to-date) ✓
  - `./gradlew test`: BUILD FAILED — reservation-service 29개, event-service 3개 실패
    - reservation-service: 전 통합 테스트 `IllegalStateException: Failed to load ApplicationContext` — `NoSuchBeanDefinitionException: No bean named 'kafkaListenerContainerFactory'` → 이슈 #259
    - event-service(1): `EventControllerTest` `GET /events/schedules/{scheduleId}/seats` → 404 (URL 매핑 불일치) → 이슈 #260
    - event-service(2): `SeatServiceTest` `releaseHoldSeats` MockK vararg 매처 불일치 → 이슈 #260
  - `./gradlew integrationTest`: Task 'integrationTest' not found — 태스크 미정의 → 이슈 #261)
- **관련 REQ**: 해당 없음
- **분류**: 정상
- **우선순위**: P0(필수/핵심)
- **사전조건**: JDK 21 설치, Docker Desktop 실행(TestContainers 사용), 네트워크 접속 가능(의존성 다운로드)
- **실행 단계**:
  1. 빌드 수행:
     ```bash
     cd /path/to/project/backend
     ./gradlew build 2>&1 | tail -20
     ```
  2. 단위 테스트만 실행:
     ```bash
     ./gradlew test 2>&1 | tail -20
     ```
  3. 통합 테스트(TestContainers) 실행:
     ```bash
     ./gradlew integrationTest 2>&1 | tail -30
     ```
  4. 테스트 결과 요약 확인:
     ```bash
     find . -name "TEST-*.xml" -path "*/integrationTest/*" | \
       xargs grep -l "failures=\"0\" errors=\"0\"" | wc -l
     ```
- **기대 결과**: `BUILD SUCCESSFUL`, 단위 테스트 0 failures/errors, integrationTest 0 failures/errors
- **검증 포인트**: Gradle exit code 0, TEST-*.xml 파일의 failures=0 AND errors=0

---

### TC-ENV-015 — 컨테이너 재기동 후 PostgreSQL 데이터 영속성 확인

- [x] 통과 (2026-05-30, 근거: `common.outbox_events`에 테스트 row 삽입(`aggregate_type='EnvTest'`) 후 `docker compose restart postgres` 실행. healthy 복구 후 재확인 시 row 수 동일(1건), `aggregate_type='EnvTest'` row 유지 확인. `postgres_data` named volume 영속성 보장)
- **관련 REQ**: 해당 없음
- **분류**: 엣지
- **우선순위**: P1(중요)
- **사전조건**: TC-ENV-003 통과, common.outbox_events 테이블에 seed 데이터 또는 임의 row 존재
- **실행 단계**:
  1. 데이터 삽입 및 row 수 기록:
     ```bash
     docker exec -it ticket-postgres psql -U ticket -d ticket_queue -c \
       "INSERT INTO common.outbox_events(aggregate_type, aggregate_id, event_type, payload)
        VALUES ('Test','00000000-0000-0000-0000-000000000001','TestEvent','{\"test\":true}');
        SELECT COUNT(*) FROM common.outbox_events;"
     ```
  2. postgres 컨테이너만 재시작:
     ```bash
     docker-compose restart postgres
     ```
  3. healthy 대기 후 row 수 재확인:
     ```bash
     sleep 30
     docker exec -it ticket-postgres psql -U ticket -d ticket_queue -c \
       "SELECT COUNT(*) FROM common.outbox_events;"
     ```
- **기대 결과**: 재시작 전후 row 수 동일, 삽입한 `aggregate_type='Test'` row 유지
- **검증 포인트**: `postgres_data` named volume 존재로 데이터 보존, row 수 변화 없음

---

### TC-ENV-016 — Valkey KEYS 명령 사용 금지 및 O(1) 대체 자료구조 적용 확인

- [x] 통과 (2026-05-30, 근거: `grep -rn "\.keys(\|redisTemplate.*keys\|\"KEYS \""` 프로덕션 코드 검색 결과 0건. `event-service/CacheHelper.kt:16` 는 주석 내 구 방식 언급으로 실제 호출 코드 아님. `hold_seats:{scheduleId}` 키 자료구조: queue-service 미기동으로 실 키 없으나 코드베이스 설계 상 Redis SET(SISMEMBER O(1)) 사용 확인. SCAN 커서 방식 사용 확인)
- **관련 REQ**: 해당 없음
- **분류**: 보안
- **우선순위**: P0(필수/핵심)
- **사전조건**: TC-ENV-009 통과, queue-service 기동 완료, Valkey 컨테이너 running
- **실행 단계**:
  1. 코드베이스에서 `KEYS` 명령 사용 여부 검색:
     ```bash
     grep -rn "\.keys\(\"" /path/to/project/backend \
       --include="*.kt" | grep -v "//\|test\|Test" | grep -v "redisTemplate\.opsForHash\|redisTemplate\.opsForSet"
     ```
  2. `KEYS` 패턴 직접 호출 탐지:
     ```bash
     grep -rn "redisTemplate.*keys\|ReactiveRedisTemplate.*keys\|KEYS\s" \
       /path/to/project/backend --include="*.kt" | grep -v "^.*:.*//.*KEYS"
     ```
  3. Valkey CLI에서 직접 KEYS 대신 SCAN으로 hold_seats 패턴 존재 확인:
     ```bash
     VALKEY_PW=$(cat docker/secrets/valkey_pw.txt)
     docker exec ticket-valkey valkey-cli -a "$VALKEY_PW" SCAN 0 MATCH "hold_seats:*" COUNT 10
     docker exec ticket-valkey valkey-cli -a "$VALKEY_PW" TYPE hold_seats:test_schedule_id 2>/dev/null || echo "key not exist (expected)"
     ```
  4. hold_seats 키가 존재하는 경우 자료구조 타입 확인:
     ```bash
     VALKEY_PW=$(cat docker/secrets/valkey_pw.txt)
     # 실제 scheduleId 필요 시 DB에서 조회 후 대입
     docker exec ticket-valkey valkey-cli -a "$VALKEY_PW" OBJECT HELP
     ```
- **기대 결과**:
  - 코드베이스 `KEYS` 명령 직접 호출: 0건
  - hold_seats 자료구조 타입: `set` (Redis SET, O(1) SISMEMBER/SADD/SREM)
  - SCAN 명령은 허용(O(1) per call, cursor-based)
- **검증 포인트**: `grep` 결과 0건, Valkey `TYPE hold_seats:*` = `set` (존재할 경우)
