#!/bin/sh
set -e

USER_SVC_PW=$(cat /run/secrets/postgres_user_pw)
EVENT_SVC_PW=$(cat /run/secrets/postgres_event_pw)
RESERVATION_SVC_PW=$(cat /run/secrets/postgres_reservation_pw)
PAYMENT_SVC_PW=$(cat /run/secrets/postgres_payment_pw)

echo "Creating service users and schemas from secrets..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL

    -- User Service
    DO \$do\$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'user_svc_user') THEN
            CREATE USER user_svc_user WITH PASSWORD '$USER_SVC_PW';
        END IF;
    END
    \$do\$;
    CREATE SCHEMA IF NOT EXISTS user_service AUTHORIZATION user_svc_user;
    -- CREATE SCHEMA IF NOT EXISTS 는 스키마가 이미 존재하면 AUTHORIZATION 절을 무시하여 소유자를
    -- 바꾸지 않는다. 잔존 볼륨 재초기화 시에도 소유자를 강제하기 위해 ALTER SCHEMA 를 명시한다. (issue #257)
    ALTER SCHEMA user_service OWNER TO user_svc_user;
    CREATE OR REPLACE FUNCTION user_service.update_timestamp() RETURNS TRIGGER AS \$\$
    BEGIN
        NEW.updated_at = now();
        RETURN NEW;
    END;
    \$\$ LANGUAGE plpgsql;
    ALTER FUNCTION user_service.update_timestamp() OWNER TO user_svc_user;

    -- Event Service
    DO \$do\$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'event_svc_user') THEN
            CREATE USER event_svc_user WITH PASSWORD '$EVENT_SVC_PW';
        END IF;
    END
    \$do\$;
    CREATE SCHEMA IF NOT EXISTS event_service AUTHORIZATION event_svc_user;
    ALTER SCHEMA event_service OWNER TO event_svc_user;
    CREATE OR REPLACE FUNCTION event_service.update_timestamp() RETURNS TRIGGER AS \$\$
    BEGIN
        NEW.updated_at = now();
        RETURN NEW;
    END;
    \$\$ LANGUAGE plpgsql;
    ALTER FUNCTION event_service.update_timestamp() OWNER TO event_svc_user;

    -- Reservation Service
    DO \$do\$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reservation_svc_user') THEN
            CREATE USER reservation_svc_user WITH PASSWORD '$RESERVATION_SVC_PW';
        END IF;
    END
    \$do\$;
    CREATE SCHEMA IF NOT EXISTS reservation_service AUTHORIZATION reservation_svc_user;
    ALTER SCHEMA reservation_service OWNER TO reservation_svc_user;
    CREATE OR REPLACE FUNCTION reservation_service.update_timestamp() RETURNS TRIGGER AS \$\$
    BEGIN
        NEW.updated_at = now();
        RETURN NEW;
    END;
    \$\$ LANGUAGE plpgsql;
    ALTER FUNCTION reservation_service.update_timestamp() OWNER TO reservation_svc_user;

    -- Payment Service
    DO \$do\$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment_svc_user') THEN
            CREATE USER payment_svc_user WITH PASSWORD '$PAYMENT_SVC_PW';
        END IF;
    END
    \$do\$;
    CREATE SCHEMA IF NOT EXISTS payment_service AUTHORIZATION payment_svc_user;
    ALTER SCHEMA payment_service OWNER TO payment_svc_user;
    CREATE OR REPLACE FUNCTION payment_service.update_timestamp() RETURNS TRIGGER AS \$\$
    BEGIN
        NEW.updated_at = now();
        RETURN NEW;
    END;
    \$\$ LANGUAGE plpgsql;
    ALTER FUNCTION payment_service.update_timestamp() OWNER TO payment_svc_user;

    -- Common (슈퍼유저 ticket 소유 유지: 모든 서비스가 공유하는 스키마)
    CREATE SCHEMA IF NOT EXISTS common;
    GRANT ALL ON SCHEMA common TO user_svc_user, event_svc_user, reservation_svc_user, payment_svc_user;

EOSQL

echo "Service users and schemas created successfully."
