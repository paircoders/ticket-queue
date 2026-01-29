#!/bin/bash
set -e

echo "=========== Kafka Topic Init Start ==========="

# Kafka 브로커 준비 대기
BOOTSTRAP_SERVER="ticket-kafka:29092"
MAX_RETRIES=30
RETRY_INTERVAL=2

echo "[Kafka] Waiting for broker to be ready..."
for i in $(seq 1 $MAX_RETRIES); do
  if /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server $BOOTSTRAP_SERVER > /dev/null 2>&1; then
    echo "[Kafka] Broker is ready!"
    break
  fi
  if [ $i -eq $MAX_RETRIES ]; then
    echo "[Kafka] ERROR: Broker not ready after $((MAX_RETRIES * RETRY_INTERVAL))s"
    exit 1
  fi
  echo "[Kafka] Waiting... ($i/$MAX_RETRIES)"
  sleep $RETRY_INTERVAL
done

# 토픽 생성 함수
create_topic() {
  local TOPIC_NAME=$1
  local PARTITIONS=$2
  local RETENTION_MS=$3

  echo "[Kafka] Creating topic: $TOPIC_NAME (partitions=$PARTITIONS, retention=${RETENTION_MS}ms)"
  /opt/kafka/bin/kafka-topics.sh --create \
    --bootstrap-server $BOOTSTRAP_SERVER \
    --topic "$TOPIC_NAME" \
    --partitions "$PARTITIONS" \
    --replication-factor 1 \
    --config retention.ms="$RETENTION_MS" \
    --if-not-exists
}

# 비즈니스 토픽 (파티션 3, 보관 3일 = 259200000ms)
create_topic "reservation.events" 3 259200000
create_topic "payment.events"     3 259200000

# DLQ 토픽 (파티션 1, 보관 7일 = 604800000ms)
create_topic "dlq.reservation" 1 604800000
create_topic "dlq.payment"     1 604800000

# 생성 결과 확인
echo ""
echo "[Kafka] Topic list:"
/opt/kafka/bin/kafka-topics.sh --list --bootstrap-server $BOOTSTRAP_SERVER

echo ""
echo "[Kafka] Topic details:"
/opt/kafka/bin/kafka-topics.sh --describe --bootstrap-server $BOOTSTRAP_SERVER

echo "=========== Kafka Topic Init Complete ==========="
