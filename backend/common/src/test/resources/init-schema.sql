-- Create common schema if not exists
CREATE SCHEMA IF NOT EXISTS common;

-- Drop existing table if exists (for test environment)
DROP TABLE IF EXISTS common.outbox_events;

-- Create outbox_events table
CREATE TABLE common.outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT false,
    published_at TIMESTAMP,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on created_at for efficient polling
CREATE INDEX idx_outbox_events_created_at ON common.outbox_events(created_at);

-- Create composite index on published and retry_count for polling query
CREATE INDEX idx_outbox_events_published_retry ON common.outbox_events(published, retry_count, created_at);

-- Create index on aggregate lookup
CREATE INDEX idx_outbox_events_aggregate ON common.outbox_events(aggregate_type, aggregate_id);
