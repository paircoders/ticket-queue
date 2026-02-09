-- Event Service DDL

-- updated_at 자동 업데이트 트리거 함수 (Event Service 전용)
CREATE OR REPLACE FUNCTION event_service.update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- 1. venues Table
CREATE TABLE event_service.venues (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    address TEXT NOT NULL,
    city VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- 인덱스
CREATE INDEX idx_venues_city ON event_service.venues(city);
CREATE INDEX idx_venues_name ON event_service.venues(name);

-- updated_at Trigger
CREATE TRIGGER trg_venues_updated_at
BEFORE UPDATE ON event_service.venues
FOR EACH ROW EXECUTE FUNCTION event_service.update_timestamp();

COMMENT ON TABLE event_service.venues IS '공연장 정보. 위치 기반 검색 지원.';
COMMENT ON COLUMN event_service.venues.id IS '공연장 UUID';
COMMENT ON COLUMN event_service.venues.name IS '공연장명';
COMMENT ON COLUMN event_service.venues.address IS '공연장 주소';
COMMENT ON COLUMN event_service.venues.city IS '도시명 (지역 필터용)';
COMMENT ON COLUMN event_service.venues.created_at IS '생성일시';
COMMENT ON COLUMN event_service.venues.updated_at IS '수정일시';


-- 2. halls Table
CREATE TABLE event_service.halls (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id UUID NOT NULL REFERENCES event_service.venues(id) ON DELETE RESTRICT,
    name VARCHAR(200) NOT NULL,
    capacity INT NOT NULL,
    seat_template JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT chk_halls_capacity CHECK (capacity > 0),
    CONSTRAINT uk_halls_venue_name UNIQUE (venue_id, name)
);

-- 인덱스
CREATE INDEX idx_halls_venue ON event_service.halls(venue_id);
CREATE INDEX idx_halls_seat_template ON event_service.halls USING GIN (seat_template);

-- updated_at Trigger
CREATE TRIGGER trg_halls_updated_at
BEFORE UPDATE ON event_service.halls
FOR EACH ROW EXECUTE FUNCTION event_service.update_timestamp();

COMMENT ON TABLE event_service.halls IS '공연장 홀 정보. JSONB 좌석 템플릿 사용.';
COMMENT ON COLUMN event_service.halls.id IS '홀 UUID';
COMMENT ON COLUMN event_service.halls.venue_id IS '공연장 UUID';
COMMENT ON COLUMN event_service.halls.name IS '홀명';
COMMENT ON COLUMN event_service.halls.capacity IS '최대 수용 인원';
COMMENT ON COLUMN event_service.halls.seat_template IS '좌석 배치 템플릿: {"rows": ["A","B"], "seatsPerRow": 20, "gradeMapping": {"A": "VIP"}}';
COMMENT ON COLUMN event_service.halls.created_at IS '생성일시';
COMMENT ON COLUMN event_service.halls.updated_at IS '수정일시';


-- 3. events Table
CREATE TABLE event_service.events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(300) NOT NULL,
    artist VARCHAR(200) NOT NULL,
    description TEXT,
    venue_id UUID NOT NULL REFERENCES event_service.venues(id) ON DELETE RESTRICT,
    hall_id UUID NOT NULL REFERENCES event_service.halls(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'PREPARING',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT chk_events_status CHECK (status IN ('PREPARING', 'OPEN', 'ENDED', 'CANCELLED'))
);

-- 인덱스
CREATE INDEX idx_events_status ON event_service.events(status);
CREATE INDEX idx_events_artist ON event_service.events(artist);
CREATE INDEX idx_events_venue ON event_service.events(venue_id);

-- 전문 검색 인덱스 (선택)
CREATE INDEX idx_events_fts ON event_service.events
    USING gin(to_tsvector('simple', title || ' ' || artist));

-- updated_at Trigger
CREATE TRIGGER trg_events_updated_at
BEFORE UPDATE ON event_service.events
FOR EACH ROW EXECUTE FUNCTION event_service.update_timestamp();

COMMENT ON TABLE event_service.events IS '공연 메타 정보. status는 공연 전체의 생명주기(노출 여부 등)를 관리.';
COMMENT ON COLUMN event_service.events.id IS '공연 UUID';
COMMENT ON COLUMN event_service.events.title IS '공연 제목';
COMMENT ON COLUMN event_service.events.artist IS '아티스트/출연진';
COMMENT ON COLUMN event_service.events.description IS '공연 설명';
COMMENT ON COLUMN event_service.events.venue_id IS '공연장 UUID';
COMMENT ON COLUMN event_service.events.hall_id IS '홀 UUID';
COMMENT ON COLUMN event_service.events.status IS 'PREPARING: 준비중(미노출), OPEN: 공개됨, ENDED: 전체 종료, CANCELLED: 전체 취소';
COMMENT ON COLUMN event_service.events.created_at IS '생성일시';
COMMENT ON COLUMN event_service.events.updated_at IS '수정일시';


-- 4. event_schedules Table
CREATE TABLE event_service.event_schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES event_service.events(id) ON DELETE CASCADE,
    play_sequence INT NOT NULL DEFAULT 1,
    event_start_at TIMESTAMP NOT NULL,
    event_end_at TIMESTAMP NOT NULL,
    sale_start_at TIMESTAMP NOT NULL,
    sale_end_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT chk_schedules_status CHECK (status IN ('UPCOMING', 'ONGOING', 'ENDED', 'CANCELLED')),
    CONSTRAINT chk_schedules_time CHECK (event_start_at < event_end_at),
    CONSTRAINT chk_schedules_sale_time CHECK (sale_start_at < sale_end_at),
    CONSTRAINT uk_schedules_event_seq UNIQUE (event_id, play_sequence)
);

-- 인덱스
CREATE INDEX idx_schedules_status_sale_start ON event_service.event_schedules(status, sale_start_at);
CREATE INDEX idx_schedules_event_date ON event_service.event_schedules(event_id, event_start_at);
CREATE INDEX idx_schedules_sale_start ON event_service.event_schedules(sale_start_at) WHERE status = 'UPCOMING';

-- updated_at Trigger
CREATE TRIGGER trg_schedules_updated_at
BEFORE UPDATE ON event_service.event_schedules
FOR EACH ROW EXECUTE FUNCTION event_service.update_timestamp();

COMMENT ON TABLE event_service.event_schedules IS '공연 회차 및 판매 일정 정보. status는 회차별 티켓 판매 상태 관리.';
COMMENT ON COLUMN event_service.event_schedules.id IS '회차 UUID';
COMMENT ON COLUMN event_service.event_schedules.event_id IS '공연 UUID';
COMMENT ON COLUMN event_service.event_schedules.play_sequence IS '회차 순번 (1회차, 2회차...)';
COMMENT ON COLUMN event_service.event_schedules.event_start_at IS '공연 시작 일시';
COMMENT ON COLUMN event_service.event_schedules.event_end_at IS '공연 종료 일시';
COMMENT ON COLUMN event_service.event_schedules.sale_start_at IS '티켓 판매 시작 일시';
COMMENT ON COLUMN event_service.event_schedules.sale_end_at IS '티켓 판매 종료 일시';
COMMENT ON COLUMN event_service.event_schedules.status IS 'UPCOMING: 판매 전, ONGOING: 판매 중, ENDED: 종료, CANCELLED: 취소';
COMMENT ON COLUMN event_service.event_schedules.created_at IS '생성일시';
COMMENT ON COLUMN event_service.event_schedules.updated_at IS '수정일시';


-- 5. seats Table
CREATE TABLE event_service.seats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_schedule_id UUID NOT NULL REFERENCES event_service.event_schedules(id) ON DELETE CASCADE,
    seat_number VARCHAR(10) NOT NULL,
    grade VARCHAR(10) NOT NULL,
    price DECIMAL(10, 0) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT chk_seats_grade CHECK (grade IN ('VIP', 'S', 'A', 'B')),
    CONSTRAINT chk_seats_status CHECK (status IN ('AVAILABLE', 'SOLD')),
    CONSTRAINT chk_seats_price CHECK (price >= 0),
    CONSTRAINT uk_seats_schedule_number UNIQUE (event_schedule_id, seat_number)
);

-- 핵심 인덱스 (조회 성능 최적화)
CREATE INDEX idx_seats_schedule_status ON event_service.seats(event_schedule_id, status);
CREATE INDEX idx_seats_grade_price ON event_service.seats(grade, price);

-- updated_at Trigger
CREATE TRIGGER trg_seats_updated_at
BEFORE UPDATE ON event_service.seats
FOR EACH ROW EXECUTE FUNCTION event_service.update_timestamp();

COMMENT ON TABLE event_service.seats IS '회차별 좌석 재고. HOLD 상태는 Redis로 관리 (seat:hold:{scheduleId}:{seatId}).';
COMMENT ON COLUMN event_service.seats.id IS '좌석 UUID';
COMMENT ON COLUMN event_service.seats.event_schedule_id IS '회차 UUID';
COMMENT ON COLUMN event_service.seats.seat_number IS '좌석 번호 (예: A-1, B-10)';
COMMENT ON COLUMN event_service.seats.grade IS '좌석 등급 (VIP/S/A/B)';
COMMENT ON COLUMN event_service.seats.price IS '좌석 가격';
COMMENT ON COLUMN event_service.seats.status IS 'AVAILABLE: 판매 가능, SOLD: 판매 완료';
COMMENT ON COLUMN event_service.seats.created_at IS '생성일시';
COMMENT ON COLUMN event_service.seats.updated_at IS '수정일시';


-- Ownership Transfer
ALTER TABLE event_service.venues OWNER TO event_svc_user;
ALTER TABLE event_service.halls OWNER TO event_svc_user;
ALTER TABLE event_service.events OWNER TO event_svc_user;
ALTER TABLE event_service.event_schedules OWNER TO event_svc_user;
ALTER TABLE event_service.seats OWNER TO event_svc_user;
