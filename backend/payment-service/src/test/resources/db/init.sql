-- 테스트용 스키마 및 테이블 초기화 (TestContainers PostgreSQL)

-- Schemas
CREATE SCHEMA IF NOT EXISTS common;
CREATE SCHEMA IF NOT EXISTS payment_service;

-- update_timestamp 함수
CREATE OR REPLACE FUNCTION payment_service.update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- common.outbox_events
CREATE TABLE IF NOT EXISTS common.outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    published      BOOLEAN               DEFAULT false,
    published_at   TIMESTAMP,
    retry_count    INT                   DEFAULT 0,
    last_error     TEXT,
    CONSTRAINT chk_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 10)
);

-- common.processed_events
CREATE TABLE IF NOT EXISTS common.processed_events (
    event_id         UUID         NOT NULL,
    consumer_service VARCHAR(50)  NOT NULL,
    aggregate_id     UUID         NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    processed_at     TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_service)
);

-- payment_service.payments
CREATE TABLE IF NOT EXISTS payment_service.payments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id        UUID          NOT NULL,
    user_id               UUID          NOT NULL,
    payment_key           VARCHAR(200)  NOT NULL UNIQUE,
    amount                DECIMAL(10,0) NOT NULL,
    payment_method        VARCHAR(20)   NOT NULL DEFAULT 'CARD',
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    portone_transaction_id VARCHAR(100),
    portone_response      JSONB,
    failure_reason        TEXT,
    paid_at               TIMESTAMP,
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    CONSTRAINT chk_payments_amount CHECK (amount >= 0),
    CONSTRAINT chk_payments_method CHECK (payment_method IN ('CARD'))
);

CREATE UNIQUE INDEX idx_payments_payment_key ON payment_service.payments(payment_key);
CREATE INDEX idx_payments_reservation    ON payment_service.payments(reservation_id);
CREATE INDEX idx_payments_user_created   ON payment_service.payments(user_id, created_at DESC);

CREATE TRIGGER trg_payments_updated_at
BEFORE UPDATE ON payment_service.payments
FOR EACH ROW EXECUTE FUNCTION payment_service.update_timestamp();
