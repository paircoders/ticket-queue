-- Test Database Initialization Script
-- Based on docker/db_init/1_common_ddl.sql

-- Create common schema
CREATE SCHEMA IF NOT EXISTS common;

-- outbox_events 테이블
CREATE TABLE IF NOT EXISTS common.outbox_events
(
    id             UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL, -- Reservation, Payment
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

-- 성능 최적화 인덱스 (Poller용)
CREATE INDEX IF NOT EXISTS idx_outbox_published_created ON common.outbox_events (published, created_at) WHERE published = false AND retry_count < 3;
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON common.outbox_events (aggregate_type, aggregate_id);

-- processed_events 테이블
CREATE TABLE IF NOT EXISTS common.processed_events
(
    event_id         UUID         NOT NULL,
    consumer_service VARCHAR(50)  NOT NULL, -- 'reservation', 'event', 'payment'
    aggregate_id     UUID         NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    processed_at     TIMESTAMP    NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, consumer_service)
);

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_processed_events_aggregate ON common.processed_events (aggregate_id, event_type);
CREATE INDEX IF NOT EXISTS idx_processed_events_processed_at ON common.processed_events (processed_at);
