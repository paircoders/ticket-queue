#!/bin/bash
set -e

echo "=========== LocalStack Init Start ==========="

# ---------------------------------------------------------
# 0. 멱등성 헬퍼: 시크릿이 이미 있으면 update, 없으면 create
#    재실행 시 create-secret 의 ResourceExistsException 으로
#    set -e 가 스크립트를 중단시키는 문제 방지 (issue #258)
#    describe-secret 은 if 조건절에서 호출하므로 set -e 의 영향을 받지 않음.
put_secret() {
    local SECRET_NAME=$1
    local DESCRIPTION=$2
    local SECRET_VALUE=$3

    if awslocal secretsmanager describe-secret --secret-id "$SECRET_NAME" > /dev/null 2>&1; then
        echo "[SecretsManager] Updating existing secret: $SECRET_NAME"
        awslocal secretsmanager update-secret \
            --secret-id "$SECRET_NAME" \
            --description "$DESCRIPTION" \
            --secret-string "$SECRET_VALUE"
    else
        echo "[SecretsManager] Creating secret: $SECRET_NAME"
        awslocal secretsmanager create-secret \
            --name "$SECRET_NAME" \
            --description "$DESCRIPTION" \
            --secret-string "$SECRET_VALUE"
    fi
}

# 1. S3 버킷 생성 (이미 존재하면 건너뜀 — 재실행 멱등성)
if awslocal s3api head-bucket --bucket event-images > /dev/null 2>&1; then
    echo "[S3] 'event-images' bucket already exists, skipping."
else
    echo "[S3] Creating 'event-images' bucket..."
    awslocal s3 mb s3://event-images
fi

# ---------------------------------------------------------
# 2. DB 패스워드 단일 시크릿 등록 함수 (put_secret 래퍼)
#    create_secret "서비스명" "시크릿파일명" "추가설명"
create_secret() {
    local SERVICE_NAME=$1
    local SECRET_FILE=$2
    local DESCRIPTION=$3

    # 시크릿 이름 규칙: {서비스명}-svc/secure-config
    local SECRET_NAME="${SERVICE_NAME}-svc/secure-config"

    # Docker Secret 파일에서 실제 비밀번호 읽기
    local DB_PASSWORD=$(cat "/run/secrets/${SECRET_FILE}")

    # JSON 생성 (valkey인 경우 키값을 다르게 할 수도 있음, 여기선 db_password로 통일하거나 분기 처리)
    local SECRET_VALUE="{\"db_password\":\"${DB_PASSWORD}\"}"

    put_secret "$SECRET_NAME" "$DESCRIPTION" "$SECRET_VALUE"
}

# ---------------------------------------------------------
# 3. 각 서비스별 시크릿 등록 실행

# (1) User Service
USER_DB_PW=$(cat /run/secrets/postgres_user_pw)
RECAPTCHA_SECRET=$(cat /run/secrets/recaptcha_secret)
put_secret "user-svc/secure-config" \
    "Secrets for User Service" \
    "{\"db_password\":\"$USER_DB_PW\", \"recaptcha_secret\":\"$RECAPTCHA_SECRET\"}"

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
PORTONE_API_SECRET=$(cat /run/secrets/portone_api_secret)
PORTONE_STORE_ID=$(cat /run/secrets/portone_store_id)
PORTONE_CHANNEL_KEY=$(cat /run/secrets/portone_channel_key)
ENC_SECRET_KEY=$(cat /run/secrets/enc_secret_key)
ENC_HASH_SALT=$(cat /run/secrets/enc_hash_salt)

put_secret "common/secure-config" \
    "Common secrets for all services" \
    "{\"valkey_password\":\"$VALKEY_PW\", \"internal_api_key\":\"$INTERNAL_API_KEY\", \"jwt_secret\":\"$JWT_SECRET\", \"portone_api_secret\":\"$PORTONE_API_SECRET\", \"portone_store_id\":\"$PORTONE_STORE_ID\", \"portone_channel_key\":\"$PORTONE_CHANNEL_KEY\", \"enc_secret_key\":\"$ENC_SECRET_KEY\", \"enc_hash_salt\":\"$ENC_HASH_SALT\"}"

echo "[SecretsManager] All secrets registered successfully."
awslocal secretsmanager list-secrets

echo "=========== LocalStack Init Complete ==========="
