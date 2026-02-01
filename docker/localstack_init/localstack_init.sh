#!/bin/bash
set -e

echo "=========== LocalStack Init Start ==========="

# 1. S3 버킷 생성
echo "[S3] Creating 'event-images' bucket..."
awslocal s3 mb s3://event-images

# ---------------------------------------------------------
# 2. Secrets Manager 등록 함수 정의
#    create_secret "서비스명" "시크릿파일명" "추가설명"
create_secret() {
    local SERVICE_NAME=$1
    local SECRET_FILE=$2
    local DESCRIPTION=$3
    
    # 시크릿 이름 규칙: ticket-{서비스명}/secure-config
    local SECRET_NAME="${SERVICE_NAME}-svc/secure-config"
    
    # Docker Secret 파일에서 실제 비밀번호 읽기
    local DB_PASSWORD=$(cat "/run/secrets/${SECRET_FILE}")
    
    # JSON 생성 (valkey인 경우 키값을 다르게 할 수도 있음, 여기선 db_password로 통일하거나 분기 처리)
    local SECRET_VALUE="{\"db_password\":\"${DB_PASSWORD}\"}"
    
    echo "[SecretsManager] Creating secret: $SECRET_NAME"
    awslocal secretsmanager create-secret \
        --name "$SECRET_NAME" \
        --description "$DESCRIPTION" \
        --secret-string "$SECRET_VALUE"
}

# ---------------------------------------------------------
# 3. 각 서비스별 시크릿 등록 실행

# (1) User Service
create_secret "user" "postgres_user_pw" "Secrets for User Service"

# (2) Event Service
create_secret "event" "postgres_event_pw" "Secrets for Event Service"

# (3) Reservation Service
create_secret "reservation" "postgres_reservation_pw" "Secrets for Reservation Service"

# (4) Payment Service
create_secret "payment" "postgres_payment_pw" "Secrets for Payment Service"

# (5) Common secrets
VALKEY_PW=$(cat /run/secrets/valkey_pw)
INTERNAL_API_KEY=$(cat /run/secrets/internal_api_key)
JWT_SECRET=$(cat /run/secrets/jwt_secret)
awslocal secretsmanager create-secret \
    --name "common/secure-config" \
    --description "Common secrets for all services" \
    --secret-string "{\"valkey_password\":\"$VALKEY_PW\", \"internal_api_key\":\"$INTERNAL_API_KEY\", \"jwt_secret\":\"$JWT_SECRET\"}"

echo "[SecretsManager] All secrets created successfully."
awslocal secretsmanager list-secrets

echo "=========== LocalStack Init Complete ==========="