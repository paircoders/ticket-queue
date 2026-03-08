-- Reservation Service DDL

-- 1. reservations Table
CREATE TABLE reservation_service.reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    schedule_id UUID NOT NULL,
    event_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(10, 0) NOT NULL,
    hold_expires_at TIMESTAMP NOT NULL,
    ticket_number VARCHAR(50) UNIQUE,
    payment_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT chk_reservations_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT chk_reservations_amount CHECK (total_amount >= 0)
);

-- 인덱스
CREATE INDEX idx_reservations_user_id ON reservation_service.reservations(user_id);
CREATE INDEX idx_reservations_schedule_id ON reservation_service.reservations(schedule_id);
CREATE INDEX idx_reservations_status ON reservation_service.reservations(status);
CREATE INDEX idx_reservations_hold_expires ON reservation_service.reservations(hold_expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_reservations_user_schedule ON reservation_service.reservations(user_id, schedule_id, status);
CREATE UNIQUE INDEX idx_reservations_ticket_number ON reservation_service.reservations(ticket_number) WHERE ticket_number IS NOT NULL;

-- updated_at Trigger
CREATE TRIGGER trg_reservations_updated_at
BEFORE UPDATE ON reservation_service.reservations
FOR EACH ROW EXECUTE FUNCTION reservation_service.update_timestamp();

COMMENT ON TABLE reservation_service.reservations IS '예매 정보. PENDING 상태에서 hold_expires_at 이내에 결제 완료 필요.';
COMMENT ON COLUMN reservation_service.reservations.id IS '예매 UUID';
COMMENT ON COLUMN reservation_service.reservations.user_id IS '사용자 UUID (user_service.users 참조, 직접 FK 없음)';
COMMENT ON COLUMN reservation_service.reservations.schedule_id IS '공연 회차 UUID (event_service.event_schedules 참조, 직접 FK 없음)';
COMMENT ON COLUMN reservation_service.reservations.event_id IS '공연 UUID (event_service.events 참조, 직접 FK 없음)';
COMMENT ON COLUMN reservation_service.reservations.status IS 'PENDING: 좌석 선점 완료(결제 대기), CONFIRMED: 결제 완료, CANCELLED: 취소';
COMMENT ON COLUMN reservation_service.reservations.total_amount IS '총 결제 금액';
COMMENT ON COLUMN reservation_service.reservations.hold_expires_at IS '좌석 선점 만료 시각 (PENDING 상태에서 5분 유효)';
COMMENT ON COLUMN reservation_service.reservations.ticket_number IS '티켓 번호 (CONFIRMED 후 발급)';
COMMENT ON COLUMN reservation_service.reservations.payment_id IS '결제 ID (결제 완료 후 저장)';
COMMENT ON COLUMN reservation_service.reservations.created_at IS '생성일시';
COMMENT ON COLUMN reservation_service.reservations.updated_at IS '수정일시';


-- 2. reservation_seats Table
CREATE TABLE reservation_service.reservation_seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id UUID NOT NULL REFERENCES reservation_service.reservations(id) ON DELETE CASCADE,
    seat_id UUID NOT NULL,
    seat_number VARCHAR(20) NOT NULL,
    grade VARCHAR(10) NOT NULL,
    price DECIMAL(10, 0) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT chk_reservation_seats_grade CHECK (grade IN ('VIP', 'S', 'A', 'B')),
    CONSTRAINT chk_reservation_seats_price CHECK (price >= 0),
    CONSTRAINT uk_reservation_seats UNIQUE (reservation_id, seat_id)
);

-- 인덱스
CREATE INDEX idx_reservation_seats_reservation_id ON reservation_service.reservation_seats(reservation_id);
CREATE INDEX idx_reservation_seats_seat ON reservation_service.reservation_seats(seat_id);

COMMENT ON TABLE reservation_service.reservation_seats IS '예매 좌석 상세. 선점된 각 좌석 정보를 저장.';
COMMENT ON COLUMN reservation_service.reservation_seats.id IS '예매 좌석 UUID';
COMMENT ON COLUMN reservation_service.reservation_seats.reservation_id IS '예매 UUID';
COMMENT ON COLUMN reservation_service.reservation_seats.seat_id IS '좌석 UUID (event_service.seats 참조, 직접 FK 없음)';
COMMENT ON COLUMN reservation_service.reservation_seats.seat_number IS '좌석 번호 (예: A-1, B-10)';
COMMENT ON COLUMN reservation_service.reservation_seats.grade IS '좌석 등급 (VIP/S/A/B)';
COMMENT ON COLUMN reservation_service.reservation_seats.price IS '좌석 가격';
COMMENT ON COLUMN reservation_service.reservation_seats.created_at IS '생성일시';


-- Ownership Transfer
ALTER TABLE reservation_service.reservations OWNER TO reservation_svc_user;
ALTER TABLE reservation_service.reservation_seats OWNER TO reservation_svc_user;
