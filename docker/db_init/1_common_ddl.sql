-- Common DDL

-- outbox_events 테이블
CREATE TABLE common.outbox_events
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
CREATE INDEX idx_outbox_published_created ON common.outbox_events (published, created_at) WHERE published = false AND retry_count < 3;
CREATE INDEX idx_outbox_aggregate ON common.outbox_events (aggregate_type, aggregate_id);

-- 테이블 코멘트
COMMENT
ON TABLE common.outbox_events IS 'Transactional Outbox 패턴. 이벤트 발행 신뢰성 보장.';
COMMENT
ON COLUMN common.outbox_events.payload IS '이벤트 데이터 (JSONB). 예: {"reservationId": "uuid", "userId": "uuid"}';


-- processed_events 테이블
CREATE TABLE common.processed_events
(
    event_id         UUID         NOT NULL,
    consumer_service VARCHAR(50)  NOT NULL, -- 'reservation', 'event', 'payment'
    aggregate_id     UUID         NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    processed_at     TIMESTAMP    NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, consumer_service)
);

-- 인덱스
CREATE INDEX idx_processed_events_aggregate ON common.processed_events (aggregate_id, event_type);
CREATE INDEX idx_processed_events_processed_at ON common.processed_events (processed_at);

-- 테이블 코멘트
COMMENT
ON TABLE common.processed_events IS 'Kafka Consumer 멱등성 보장. (event_id, consumer_service) 중복 시 Constraint Violation.';