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
COMMENT ON TABLE common.outbox_events IS 'Transactional Outbox 패턴. 이벤트 발행 신뢰성 보장.';

-- 컬럼 코멘트
COMMENT ON COLUMN common.outbox_events.id IS '이벤트 고유 ID (Transactional Outbox)';
COMMENT ON COLUMN common.outbox_events.aggregate_type IS '이벤트 발행 주체 (Reservation/Payment/Event)';
COMMENT ON COLUMN common.outbox_events.aggregate_id IS '이벤트 발행 대상 엔티티 ID';
COMMENT ON COLUMN common.outbox_events.event_type IS '이벤트 타입 (PaymentSuccess/PaymentFailed/ReservationCancelled 등)';
COMMENT ON COLUMN common.outbox_events.payload IS '이벤트 데이터 (JSONB). 예: {"reservationId": "uuid", "userId": "uuid"}';
COMMENT ON COLUMN common.outbox_events.created_at IS '이벤트 생성 일시';
COMMENT ON COLUMN common.outbox_events.published IS '이벤트 발행 여부 (false: 미발행, true: 발행 완료)';
COMMENT ON COLUMN common.outbox_events.published_at IS '이벤트 발행 일시';
COMMENT ON COLUMN common.outbox_events.retry_count IS '발행 실패 시 재시도 횟수 (최대 3회)';
COMMENT ON COLUMN common.outbox_events.last_error IS '마지막 발행 실패 에러 메시지';


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
COMMENT ON TABLE common.processed_events IS 'Kafka Consumer 멱등성 보장. (event_id, consumer_service) 중복 시 Constraint Violation.';

-- 컬럼 코멘트
COMMENT ON COLUMN common.processed_events.event_id IS 'Kafka로부터 수신한 이벤트 고유 ID';
COMMENT ON COLUMN common.processed_events.consumer_service IS '이벤트 처리 서비스 (reservation/payment/event)';
COMMENT ON COLUMN common.processed_events.aggregate_id IS '이벤트 발행 대상 엔티티 ID (추적용)';
COMMENT ON COLUMN common.processed_events.event_type IS '이벤트 타입 (멱등성 검증용)';
COMMENT ON COLUMN common.processed_events.processed_at IS '이벤트 처리 완료 일시';