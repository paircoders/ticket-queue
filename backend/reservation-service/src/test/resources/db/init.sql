-- 테스트용 스키마 및 테이블 초기화 (TestContainers PostgreSQL)

-- Schemas
CREATE SCHEMA IF NOT EXISTS common;
CREATE SCHEMA IF NOT EXISTS reservation_service;

-- update_timestamp 함수 (trigger용)
CREATE OR REPLACE FUNCTION reservation_service.update_timestamp()
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

-- reservation_service.reservations
CREATE TABLE IF NOT EXISTS reservation_service.reservations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL,
    schedule_id    UUID         NOT NULL,
    event_id       UUID         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    total_amount   DECIMAL(10,0) NOT NULL,
    hold_expires_at TIMESTAMP   NOT NULL,
    ticket_number  VARCHAR(50)  UNIQUE,
    payment_id     UUID,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_reservations_status CHECK (status IN ('PENDING','CONFIRMED','CANCELLED')),
    CONSTRAINT chk_reservations_amount CHECK (total_amount >= 0)
);

CREATE OR REPLACE TRIGGER trg_reservations_updated_at
BEFORE UPDATE ON reservation_service.reservations
FOR EACH ROW EXECUTE FUNCTION reservation_service.update_timestamp();

-- reservation_service.reservation_seats
CREATE TABLE IF NOT EXISTS reservation_service.reservation_seats (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID         NOT NULL REFERENCES reservation_service.reservations(id) ON DELETE CASCADE,
    seat_id        UUID         NOT NULL,
    seat_number    VARCHAR(20)  NOT NULL,
    grade          VARCHAR(10)  NOT NULL,
    price          DECIMAL(10,0) NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_reservation_seats_grade CHECK (grade IN ('VIP','S','A','B')),
    CONSTRAINT chk_reservation_seats_price CHECK (price >= 0),
    CONSTRAINT uk_reservation_seats UNIQUE (reservation_id, seat_id)
);
