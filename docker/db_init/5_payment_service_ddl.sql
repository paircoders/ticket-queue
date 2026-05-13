-- Payment Service DDL

-- payments 테이블
CREATE TABLE payment_service.payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    payment_key VARCHAR(200) NOT NULL UNIQUE,
    amount DECIMAL(10, 0) NOT NULL,
    payment_method VARCHAR(20) NOT NULL DEFAULT 'CARD',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    portone_transaction_id VARCHAR(100),
    portone_response JSONB,
    failure_reason TEXT,
    paid_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    CONSTRAINT chk_payments_amount CHECK (amount >= 0),
    CONSTRAINT chk_payments_method CHECK (payment_method IN ('CARD'))
);

-- 인덱스
CREATE UNIQUE INDEX idx_payments_payment_key ON payment_service.payments(payment_key);
CREATE INDEX idx_payments_reservation ON payment_service.payments(reservation_id);
CREATE INDEX idx_payments_user_created ON payment_service.payments(user_id, created_at DESC);
CREATE INDEX idx_payments_status_created ON payment_service.payments(status, created_at DESC);
CREATE INDEX idx_payments_portone_response ON payment_service.payments USING GIN (portone_response);
CREATE UNIQUE INDEX idx_payments_reservation_pending_success
    ON payment_service.payments(reservation_id)
    WHERE status IN ('PENDING', 'SUCCESS');

-- updated_at 트리거 (payment_service.update_timestamp() 함수는 0_init_users_and_schemas.sh에서 이미 생성됨)
CREATE TRIGGER trg_payments_updated_at
BEFORE UPDATE ON payment_service.payments
FOR EACH ROW EXECUTE FUNCTION payment_service.update_timestamp();

-- 테이블 코멘트
COMMENT ON TABLE payment_service.payments IS '결제 정보. payment_key로 멱등성 보장.';
COMMENT ON COLUMN payment_service.payments.payment_key IS '클라이언트 생성 멱등성 키. 중복 결제 방지.';
COMMENT ON COLUMN payment_service.payments.portone_response IS 'PortOne API 응답 전체 (JSONB). 디버깅 및 감사용.';

-- Ownership Transfer
ALTER TABLE payment_service.payments OWNER TO payment_svc_user;
