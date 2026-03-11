/**
 * E2E 테스트용 공연 데이터 시딩 헬퍼
 *
 * event_service 스키마에 테스트 데이터를 직접 INSERT합니다.
 * 고정 UUID를 사용하여 idempotent하게 동작합니다 (이미 존재하면 스킵).
 */

import { Client } from 'pg'

function getRequiredEnv(key: string): string {
  const value = process.env[key]
  if (!value) {
    throw new Error(`[seed-event] 필수 환경변수 누락: ${key}. .env.local 또는 CI 설정을 확인하세요.`)
  }
  return value
}

/** E2E 테스트에서 직접 URL 접근에 사용되는 고정 UUID */
export const TEST_EVENT_IDS = {
  venueId: '00000000-0000-0000-0001-000000000001',
  hallId: '00000000-0000-0000-0001-000000000002',
  eventId: '00000000-0000-0000-0001-000000000003',
  schedule1Id: '00000000-0000-0000-0001-000000000004',
  schedule2Id: '00000000-0000-0000-0001-000000000005',
} as const

export const TEST_EVENT = {
  title: 'E2E 테스트 공연',
  artist: 'E2E 테스트 아티스트',
  description: 'E2E 테스트용 공연 설명입니다.',
  venueName: 'E2E 테스트 공연장',
  hallName: 'E2E 테스트 홀',
} as const

/**
 * DB에 테스트 공연 데이터를 삽입합니다.
 * 이미 존재하면 스킵합니다 (eventId 기준).
 */
export async function seedTestEvent(): Promise<void> {
  const client = new Client({
    host: getRequiredEnv('DB_HOST'),
    port: parseInt(getRequiredEnv('DB_PORT'), 10),
    database: getRequiredEnv('DB_NAME'),
    user: getRequiredEnv('DB_USER'),
    password: getRequiredEnv('DB_PASSWORD'),
    options: '-c search_path=event_service',
  })

  try {
    await client.connect()

    // 이미 존재하면 스킵
    const existing = await client.query(
      'SELECT id FROM event_service.events WHERE id = $1',
      [TEST_EVENT_IDS.eventId],
    )
    if (existing.rows.length > 0) {
      console.log(`[seed-event] 테스트 공연 이미 존재, 스킵: ${TEST_EVENT_IDS.eventId}`)
      return
    }

    // 1. Venue 삽입
    await client.query(
      `INSERT INTO event_service.venues (id, name, address, city)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (id) DO NOTHING`,
      [TEST_EVENT_IDS.venueId, TEST_EVENT.venueName, '서울특별시 테스트구 테스트로 1', 'SEOUL'],
    )

    // 2. Hall 삽입
    const seatTemplate = JSON.stringify({
      rows: ['A', 'B'],
      seatsPerRow: 3,
      gradeMapping: { A: 'VIP', B: 'S' },
    })
    await client.query(
      `INSERT INTO event_service.halls (id, venue_id, name, capacity, seat_template)
       VALUES ($1, $2, $3, $4, $5::jsonb)
       ON CONFLICT (id) DO NOTHING`,
      [TEST_EVENT_IDS.hallId, TEST_EVENT_IDS.venueId, TEST_EVENT.hallName, 6, seatTemplate],
    )

    // 3. Event 삽입
    await client.query(
      `INSERT INTO event_service.events (id, title, artist, description, venue_id, hall_id, status)
       VALUES ($1, $2, $3, $4, $5, $6, 'OPEN')`,
      [
        TEST_EVENT_IDS.eventId,
        TEST_EVENT.title,
        TEST_EVENT.artist,
        TEST_EVENT.description,
        TEST_EVENT_IDS.venueId,
        TEST_EVENT_IDS.hallId,
      ],
    )

    // 4. EventSchedule 1 삽입 (UPCOMING, 매진 아님)
    await client.query(
      `INSERT INTO event_service.event_schedules
         (id, event_id, play_sequence, event_start_at, event_end_at, sale_start_at, sale_end_at, status)
       VALUES ($1, $2, 1, $3, $4, $5, $6, 'UPCOMING')`,
      [
        TEST_EVENT_IDS.schedule1Id,
        TEST_EVENT_IDS.eventId,
        '2026-06-01T19:00:00',
        '2026-06-01T22:00:00',
        '2026-05-01T20:00:00',
        '2026-05-31T23:59:59',
      ],
    )

    // 5. EventSchedule 2 삽입 (UPCOMING, 매진 처리용)
    await client.query(
      `INSERT INTO event_service.event_schedules
         (id, event_id, play_sequence, event_start_at, event_end_at, sale_start_at, sale_end_at, status)
       VALUES ($1, $2, 2, $3, $4, $5, $6, 'UPCOMING')`,
      [
        TEST_EVENT_IDS.schedule2Id,
        TEST_EVENT_IDS.eventId,
        '2026-06-02T17:00:00',
        '2026-06-02T20:00:00',
        '2026-05-02T20:00:00',
        '2026-06-01T23:59:59',
      ],
    )

    // 6. Schedule 1 좌석 삽입 (AVAILABLE)
    const schedule1Seats = [
      ['A-1', 'VIP', 150000, 'AVAILABLE'],
      ['A-2', 'VIP', 150000, 'AVAILABLE'],
      ['A-3', 'VIP', 150000, 'AVAILABLE'],
      ['B-1', 'S', 120000, 'AVAILABLE'],
      ['B-2', 'S', 120000, 'AVAILABLE'],
      ['B-3', 'S', 120000, 'AVAILABLE'],
    ]
    for (const [seatNumber, grade, price, status] of schedule1Seats) {
      await client.query(
        `INSERT INTO event_service.seats (event_schedule_id, seat_number, grade, price, status)
         VALUES ($1, $2, $3, $4, $5)`,
        [TEST_EVENT_IDS.schedule1Id, seatNumber, grade, price, status],
      )
    }

    // 7. Schedule 2 좌석 삽입 (전부 SOLD → isSoldOut: true)
    const schedule2Seats = [
      ['A-1', 'VIP', 150000, 'SOLD'],
      ['A-2', 'VIP', 150000, 'SOLD'],
      ['A-3', 'VIP', 150000, 'SOLD'],
      ['B-1', 'S', 120000, 'SOLD'],
      ['B-2', 'S', 120000, 'SOLD'],
      ['B-3', 'S', 120000, 'SOLD'],
    ]
    for (const [seatNumber, grade, price, status] of schedule2Seats) {
      await client.query(
        `INSERT INTO event_service.seats (event_schedule_id, seat_number, grade, price, status)
         VALUES ($1, $2, $3, $4, $5)`,
        [TEST_EVENT_IDS.schedule2Id, seatNumber, grade, price, status],
      )
    }

    console.log(`[seed-event] 테스트 공연 생성 완료: ${TEST_EVENT_IDS.eventId}`)
  } catch (err) {
    console.error('[seed-event] DB 시딩 실패:', (err as Error).message)
    throw err
  } finally {
    await client.end().catch(() => {})
  }
}
