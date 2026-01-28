#!/bin/bash
set -e

USER_SVC_PW=$(cat /run/secrets/postgres_user_pw)
EVENT_SVC_PW=$(cat /run/secrets/postgres_event_pw)
QUEUE_SVC_PW=$(cat /run/secrets/postgres_queue_pw)
RESERVATION_SVC_PW=$(cat /run/secrets/postgres_reservation_pw)
PAYMENT_SVC_PW=$(cat /run/secrets/postgres_payment_pw)

echo "Creating service users and schemas from secrets..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL

    -- User Service
    CREATE USER user_svc_user WITH PASSWORD '$USER_SVC_PW';
    CREATE SCHEMA IF NOT EXISTS user_service AUTHORIZATION user_svc_user;

    -- Event Service
    CREATE USER event_svc_user WITH PASSWORD '$EVENT_SVC_PW';
    CREATE SCHEMA IF NOT EXISTS event_service AUTHORIZATION event_svc_user;

    -- Queue Service
    CREATE USER queue_svc_user WITH PASSWORD '$QUEUE_SVC_PW';
    CREATE SCHEMA IF NOT EXISTS queue_service AUTHORIZATION queue_svc_user;

    -- Reservation Service
    CREATE USER reservation_svc_user WITH PASSWORD '$RESERVATION_SVC_PW';
    CREATE SCHEMA IF NOT EXISTS reservation_service AUTHORIZATION reservation_svc_user;

    -- Payment Service
    CREATE USER payment_svc_user WITH PASSWORD '$PAYMENT_SVC_PW';
    CREATE SCHEMA IF NOT EXISTS payment_service AUTHORIZATION payment_svc_user;

    -- Common
    CREATE SCHEMA IF NOT EXISTS common;
    GRANT ALL ON SCHEMA common TO user_svc_user, event_svc_user, queue_svc_user, reservation_svc_user, payment_svc_user;

EOSQL

echo "Service users and schemas created successfully."