#!/bin/bash

echo "=========== LocalStack Init Start ==========="

# 1. S3 버킷 생성 (콘서트 포스터 이미지 저장용)
echo "[S3] Creating 'event-images' bucket..."
awslocal s3 mb s3://event-images

# S3 버킷 리스트 확인
echo "[S3] List buckets:"
awslocal s3 ls

# ---------------------------------------------------------

# 2. Secrets Manager 시크릿 생성 (DB 접속 정보, 결제 키 등)

SECRET_NAME="ticket-queue/secure-config"
SECRET_VALUE='{"db_password":"ticket@1234"}'

echo "[SecretsManager] Creating secret: $SECRET_NAME"
awslocal secretsmanager create-secret \
    --name $SECRET_NAME \
    --description "MSA Ticket Queue Local Secrets" \
    --secret-string "$SECRET_VALUE"

# 생성된 시크릿 확인
echo "[SecretsManager] Describe secret:"
awslocal secretsmanager describe-secret --secret-id $SECRET_NAME

echo "=========== LocalStack Init Complete ==========="