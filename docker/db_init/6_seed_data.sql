-- ============================================================
-- Local Test Seed Data
-- ============================================================
-- docker-compose up 시 DDL 스크립트(1~5번) 이후 자동 실행됨.
-- docker-compose down -v && up -d 반복 실행에도 안전하도록
-- INSERT ... ON CONFLICT DO NOTHING 사용.
--
-- User Service 데이터는 AES 암호화 복잡성으로 제외.
-- user_id는 아래 고정 UUID 사용. 실제 테스트 시 API로 가입한
-- 사용자의 UUID로 UPDATE하거나 그대로 참조해도 무방.
--
-- 고정 UUID 참조표:
--   venue     : a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11
--   hall      : a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12
--   event(OPEN)       : a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20
--   event(PREPARING)  : a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a21
--   schedule-1(ONGOING)  : a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30
--   schedule-2(UPCOMING) : a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a31
--   schedule-3(ENDED)    : a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a32
--   user-a    : b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01
--   res-1(PENDING)    : c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c01
--   res-2(CONFIRMED)  : c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c02
--   res-3(CANCELLED)  : c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c03
--   pay-1(SUCCESS)    : d0eebc99-9c0b-4ef8-bb6d-6bb9bd380d01
--   pay-2(FAILED)     : d0eebc99-9c0b-4ef8-bb6d-6bb9bd380d02
--   outbox-1(published)   : e0eebc99-9c0b-4ef8-bb6d-6bb9bd380e01
--   outbox-2(unpublished) : e0eebc99-9c0b-4ef8-bb6d-6bb9bd380e02
-- ============================================================


-- ============================================================
-- 1. Event Service
-- ============================================================

INSERT INTO event_service.venues (id, name, address, city)
VALUES (
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    '잠실 주경기장',
    '서울특별시 송파구 올림픽로 25',
    '서울'
) ON CONFLICT (id) DO NOTHING;


INSERT INTO event_service.halls (id, venue_id, name, capacity, seat_template)
VALUES (
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12',
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    '메인홀',
    5000,
    '{"rows":["A","B","C","D","E"],"seatsPerRow":20,"gradeMapping":{"A":"VIP","B":"S","C":"A","D":"B","E":"B"}}'
) ON CONFLICT (id) DO NOTHING;


INSERT INTO event_service.events (id, title, artist, description, venue_id, hall_id, status)
VALUES
    (
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20',
        '2026 K-POP 월드 투어 서울',
        'STELLAR',
        '최정상 K-POP 아티스트의 단독 콘서트.',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12',
        'OPEN'
    ),
    (
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a21',
        '2026 여름 페스티벌',
        'NOVA',
        '준비 중인 신규 공연입니다.',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12',
        'PREPARING'
    )
ON CONFLICT (id) DO NOTHING;


-- schedule-1: 현재 판매 중 (ONGOING) — 공연은 30일 후
-- schedule-2: 7일 후부터 판매 예정 (UPCOMING) — 공연은 31일 후
-- schedule-3: 판매 종료된 지난 회차 (ENDED) — 공연은 30일 전
INSERT INTO event_service.event_schedules
    (id, event_id, play_sequence, event_start_at, event_end_at, sale_start_at, sale_end_at, status)
VALUES
    (
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20',
        1,
        NOW() + INTERVAL '30 days',
        NOW() + INTERVAL '30 days' + INTERVAL '3 hours',
        NOW() - INTERVAL '30 days',
        NOW() + INTERVAL '29 days',
        'ONGOING'
    ),
    (
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a31',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20',
        2,
        NOW() + INTERVAL '31 days',
        NOW() + INTERVAL '31 days' + INTERVAL '3 hours',
        NOW() + INTERVAL '7 days',
        NOW() + INTERVAL '30 days',
        'UPCOMING'
    ),
    (
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a32',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20',
        3,
        NOW() - INTERVAL '30 days',
        NOW() - INTERVAL '30 days' + INTERVAL '3 hours',
        NOW() - INTERVAL '60 days',
        NOW() - INTERVAL '31 days',
        'ENDED'
    )
ON CONFLICT (id) DO NOTHING;


-- schedule-1 좌석 100개 (A~E행 각 20개)
-- A행: VIP 150,000원 (전체 AVAILABLE)
INSERT INTO event_service.seats (event_schedule_id, seat_number, grade, price, status)
SELECT
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
    'A-' || n,
    'VIP',
    150000,
    'AVAILABLE'
FROM generate_series(1, 20) AS n
ON CONFLICT (event_schedule_id, seat_number) DO NOTHING;

-- B행: S 120,000원 (B-1 SOLD, 나머지 AVAILABLE)
INSERT INTO event_service.seats (event_schedule_id, seat_number, grade, price, status)
SELECT
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
    'B-' || n,
    'S',
    120000,
    CASE WHEN n = 1 THEN 'SOLD' ELSE 'AVAILABLE' END
FROM generate_series(1, 20) AS n
ON CONFLICT (event_schedule_id, seat_number) DO NOTHING;

-- C행: A등급 99,000원 (전체 AVAILABLE)
INSERT INTO event_service.seats (event_schedule_id, seat_number, grade, price, status)
SELECT
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
    'C-' || n,
    'A',
    99000,
    'AVAILABLE'
FROM generate_series(1, 20) AS n
ON CONFLICT (event_schedule_id, seat_number) DO NOTHING;

-- D행: B등급 70,000원 (전체 AVAILABLE)
INSERT INTO event_service.seats (event_schedule_id, seat_number, grade, price, status)
SELECT
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
    'D-' || n,
    'B',
    70000,
    'AVAILABLE'
FROM generate_series(1, 20) AS n
ON CONFLICT (event_schedule_id, seat_number) DO NOTHING;

-- E행: B등급 70,000원 (전체 AVAILABLE)
INSERT INTO event_service.seats (event_schedule_id, seat_number, grade, price, status)
SELECT
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
    'E-' || n,
    'B',
    70000,
    'AVAILABLE'
FROM generate_series(1, 20) AS n
ON CONFLICT (event_schedule_id, seat_number) DO NOTHING;


-- ============================================================
-- 2. Reservation Service
-- ============================================================

INSERT INTO reservation_service.reservations
    (id, user_id, schedule_id, event_id, status, total_amount, hold_expires_at, ticket_number, payment_id)
VALUES
    (
        -- res-1: PENDING (결제 대기 중, A-3 좌석 hold)
        'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c01',
        'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20',
        'PENDING', 150000, NOW() + INTERVAL '5 minutes',
        NULL, NULL
    ),
    (
        -- res-2: CONFIRMED (결제 완료, B-1 좌석 SOLD)
        'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c02',
        'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20',
        'CONFIRMED', 120000, NOW() + INTERVAL '5 minutes',
        'T20260601-TEST001',
        'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380d01'
    ),
    (
        -- res-3: CANCELLED (결제 실패로 취소, D-1 좌석 반환)
        'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c03',
        'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30',
        'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a20',
        'CANCELLED', 70000, NOW() - INTERVAL '1 hour',
        NULL, 'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380d02'
    )
ON CONFLICT (id) DO NOTHING;


-- seat_id는 event_service.seats를 참조 (직접 FK 없음, seat_number로 조회)
INSERT INTO reservation_service.reservation_seats
    (reservation_id, seat_id, seat_number, grade, price)
SELECT
    'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c01',
    s.id, 'A-3', 'VIP', 150000
FROM event_service.seats s
WHERE s.event_schedule_id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30'
  AND s.seat_number = 'A-3'
ON CONFLICT (reservation_id, seat_id) DO NOTHING;

INSERT INTO reservation_service.reservation_seats
    (reservation_id, seat_id, seat_number, grade, price)
SELECT
    'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c02',
    s.id, 'B-1', 'S', 120000
FROM event_service.seats s
WHERE s.event_schedule_id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30'
  AND s.seat_number = 'B-1'
ON CONFLICT (reservation_id, seat_id) DO NOTHING;

INSERT INTO reservation_service.reservation_seats
    (reservation_id, seat_id, seat_number, grade, price)
SELECT
    'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c03',
    s.id, 'D-1', 'B', 70000
FROM event_service.seats s
WHERE s.event_schedule_id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30'
  AND s.seat_number = 'D-1'
ON CONFLICT (reservation_id, seat_id) DO NOTHING;


-- ============================================================
-- 3. Payment Service
-- ============================================================

INSERT INTO payment_service.payments
    (id, reservation_id, user_id, payment_key, amount, payment_method, status,
     portone_transaction_id, portone_response, failure_reason, paid_at)
VALUES
    (
        -- pay-1: SUCCESS (res-2)
        'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380d01',
        'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c02',
        'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01',
        'pm-seed-test-001',
        120000, 'CARD', 'SUCCESS',
        'tx-seed-test-001',
        '{"code":"0","message":"success","transactionId":"tx-seed-test-001","amount":120000,"status":"PAID","method":"CARD"}'::jsonb,
        NULL,
        NOW() - INTERVAL '1 hour'
    ),
    (
        -- pay-2: FAILED (res-3)
        'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380d02',
        'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c03',
        'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01',
        'pm-seed-test-002',
        70000, 'CARD', 'FAILED',
        NULL, NULL,
        '카드 한도 초과',
        NULL
    )
ON CONFLICT (id) DO NOTHING;


-- ============================================================
-- 4. Common Schema
-- ============================================================

-- outbox-1: PaymentSuccess (발행 완료)
-- outbox-2: ReservationCancelled (미발행 — Poller 동작 테스트용)
INSERT INTO common.outbox_events
    (id, aggregate_type, aggregate_id, event_type, payload, published, published_at)
VALUES
    (
        'e0eebc99-9c0b-4ef8-bb6d-6bb9bd380e01',
        'Payment',
        'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380d01',
        'PaymentSuccess',
        '{"paymentId":"d0eebc99-9c0b-4ef8-bb6d-6bb9bd380d01","reservationId":"c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c02","userId":"b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01","amount":120000}'::jsonb,
        true,
        NOW() - INTERVAL '59 minutes'
    ),
    (
        'e0eebc99-9c0b-4ef8-bb6d-6bb9bd380e02',
        'Reservation',
        'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c03',
        'ReservationCancelled',
        '{"reservationId":"c0eebc99-9c0b-4ef8-bb6d-6bb9bd380c03","userId":"b0eebc99-9c0b-4ef8-bb6d-6bb9bd380b01","scheduleId":"a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a30","reason":"payment_failed"}'::jsonb,
        false,
        NULL
    )
ON CONFLICT (id) DO NOTHING;


-- reservation-service가 PaymentSuccess 이벤트 처리 완료한 기록
INSERT INTO common.processed_events
    (event_id, consumer_service, aggregate_id, event_type)
VALUES
    (
        'e0eebc99-9c0b-4ef8-bb6d-6bb9bd380e01',
        'reservation',
        'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380d01',
        'PaymentSuccess'
    )
ON CONFLICT (event_id, consumer_service) DO NOTHING;
