#!/bin/bash
# ============================================================
# Valkey(Redis) 로컬 테스트용 시드 데이터 삽입 스크립트
# ============================================================
# 사전 조건:
#   1. docker-compose up -d 로 인프라가 기동된 상태
#   2. docker/db_init/6_seed_data.sql 이 적용된 상태 (DB seed 완료)
#
# 실행 방법:
#   bash docker/scripts/valkey_seed.sh
#
# 삽입되는 데이터:
#   - schedule-1 대기열 (queue:a0eebc99-...-a30)
#   - user-a Queue Token (queue:token:{token})
#   - A-3 좌석 선점 hold (seat:hold:{scheduleId}:{seatId})
#   - hold_seats SET
#
# 참고: seat_id(A-3)는 DB에서 동적으로 조회합니다.
#       DB seed가 완료되지 않은 상태에서 실행하면 A-3 hold는 스킵됩니다.
# ============================================================

set -e

CONTAINER="ticket-valkey"
SCHEDULE_ID="a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30"
USER_A_ID="b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01"
USER_B_ID="b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b02"
TOKEN_USER_A="qr-seed-token-user-a-001"

# Valkey 비밀번호 읽기 (secrets 파일이 없으면 빈 값으로 진행)
VALKEY_PW=""
if [ -f "docker/secrets/valkey_pw.txt" ]; then
    VALKEY_PW=$(cat docker/secrets/valkey_pw.txt)
fi

if [ -n "$VALKEY_PW" ]; then
    CLI="docker exec $CONTAINER valkey-cli --no-auth-warning -a $VALKEY_PW"
else
    CLI="docker exec $CONTAINER valkey-cli"
fi

echo "=== Valkey 시드 데이터 삽입 시작 ==="

# ── 1. schedule-1 대기열 (Sorted Set)
# Score: Unix timestamp (진입 순서 보장)
echo "[1/4] 대기열(queue:${SCHEDULE_ID}) 삽입..."
NOW_TS=$(date +%s)
$CLI ZADD "queue:${SCHEDULE_ID}" \
    $((NOW_TS - 60)) "$USER_A_ID" \
    $((NOW_TS - 30)) "$USER_B_ID"

# ── 2. Queue Token (user-a 전용, TTL 600초)
echo "[2/4] Queue Token 삽입 (TTL 600s)..."
TOKEN_PAYLOAD="{\"userId\":\"${USER_A_ID}\",\"scheduleId\":\"${SCHEDULE_ID}\",\"type\":\"RESERVATION\",\"issuedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
$CLI SET "queue:token:${TOKEN_USER_A}" "$TOKEN_PAYLOAD" EX 600
$CLI SET "queue:user-token:${USER_A_ID}" "$TOKEN_USER_A" EX 600
$CLI SET "queue:active:${USER_A_ID}" "$SCHEDULE_ID" EX 600

# ── 3. 활성 schedule SET
$CLI SADD "queue:active-schedules" "$SCHEDULE_ID"

# ── 4. A-3 좌석 선점 hold (seat_id는 DB에서 조회)
echo "[3/4] 좌석 선점 hold 키 삽입 (TTL 300s)..."

DB_CONTAINER="ticket-postgres"
SEAT_ID=$(docker exec "$DB_CONTAINER" psql -U ticket -d ticket_queue -t -c \
    "SELECT id FROM event_service.seats \
     WHERE event_schedule_id = '${SCHEDULE_ID}' AND seat_number = 'A-3';" \
    2>/dev/null | tr -d ' \n')

if [ -n "$SEAT_ID" ]; then
    $CLI SET "seat:hold:${SCHEDULE_ID}:${SEAT_ID}" "$USER_A_ID" EX 300
    $CLI SADD "hold_seats:${SCHEDULE_ID}" "$SEAT_ID"
    $CLI EXPIRE "hold_seats:${SCHEDULE_ID}" 600
    echo "    A-3 좌석 hold 완료 (seat_id: ${SEAT_ID})"
else
    echo "    [SKIP] A-3 seat_id를 DB에서 찾을 수 없습니다. DB seed가 완료됐는지 확인하세요."
fi

# ── 5. 결과 확인
echo ""
echo "[4/4] 삽입 결과 확인..."
echo "--- 대기열 (queue:${SCHEDULE_ID}) ---"
$CLI ZRANGE "queue:${SCHEDULE_ID}" 0 -1 WITHSCORES

echo "--- Queue Token ---"
$CLI GET "queue:token:${TOKEN_USER_A}"

echo "--- hold_seats:${SCHEDULE_ID} ---"
$CLI SMEMBERS "hold_seats:${SCHEDULE_ID}"

echo ""
echo "=== Valkey 시드 데이터 삽입 완료 ==="
