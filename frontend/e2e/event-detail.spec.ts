/**
 * Issue #116 — 공연 상세 페이지 E2E 테스트
 *
 * 테스트 전제:
 *   - Docker 인프라 실행 중 (PostgreSQL:5432)
 *   - API Gateway (8080) + Event Service 실행 중
 *   - Playwright webServer가 자동으로 Next.js dev 서버 실행
 *
 * SSR 전략:
 *   - SSR 페이지는 서버에서 fetch()를 직접 호출하므로 page.route()로 인터셉트 불가
 *   - PostgreSQL에 직접 테스트 데이터를 INSERT하여 실제 렌더링 결과 검증
 */

import { test, expect } from '@playwright/test'
import { seedTestEvent, TEST_EVENT_IDS, TEST_EVENT } from './helpers/seed-event'

const EVENT_URL = `/events/${TEST_EVENT_IDS.eventId}`
const NOT_FOUND_URL = `/events/00000000-0000-0000-0000-000000000999`

test.describe('Issue #116 — 공연 상세 페이지 (SSR + SEO + JSON-LD)', () => {
  test.beforeAll(async () => {
    // DB에 테스트 공연 데이터 삽입 (이미 있으면 스킵)
    await seedTestEvent()
  })

  // ────────────────────────────────────────────────────────
  // 1. 페이지 렌더링 검증
  // ────────────────────────────────────────────────────────
  test('공연 상세 페이지가 SSR로 올바르게 렌더링된다', async ({ page }) => {
    await page.goto(EVENT_URL)

    // 공연 제목 (h1)
    await expect(page.locator('h1')).toHaveText(TEST_EVENT.title)

    // 아티스트
    await expect(page.getByText(TEST_EVENT.artist).first()).toBeVisible()

    // 공연장 정보
    await expect(page.getByText(new RegExp(TEST_EVENT.venueName))).toBeVisible()
    await expect(page.getByText(new RegExp(TEST_EVENT.hallName))).toBeVisible()

    // 공연 설명
    await expect(page.getByText(TEST_EVENT.description)).toBeVisible()
  })

  // ────────────────────────────────────────────────────────
  // 2. SEO 메타 태그 검증
  // ────────────────────────────────────────────────────────
  test('SEO 메타 태그와 canonical URL이 올바르게 설정된다', async ({ page }) => {
    await page.goto(EVENT_URL)

    // <title> — 루트 레이아웃 template "%s | Ticket Queue" 적용
    await expect(page).toHaveTitle(`${TEST_EVENT.title} | Ticket Queue`)

    // Open Graph 태그
    const ogTitle = page.locator('meta[property="og:title"]')
    await expect(ogTitle).toHaveAttribute('content', TEST_EVENT.title)

    const ogDescription = page.locator('meta[property="og:description"]')
    await expect(ogDescription).toHaveAttribute('content', /.+/)

    // Canonical URL
    const canonical = page.locator('link[rel="canonical"]')
    await expect(canonical).toHaveAttribute(
      'href',
      `https://ticket-queue.com/events/${TEST_EVENT_IDS.eventId}`,
    )
  })

  // ────────────────────────────────────────────────────────
  // 3. JSON-LD Structured Data 검증
  // ────────────────────────────────────────────────────────
  test('JSON-LD Structured Data가 올바른 Schema.org Event 형식으로 삽입된다', async ({ page }) => {
    await page.goto(EVENT_URL)

    const scriptContent = await page
      .locator('script[type="application/ld+json"]')
      .first()
      .textContent()

    expect(scriptContent).not.toBeNull()
    const jsonLd = JSON.parse(scriptContent!)

    expect(jsonLd['@context']).toBe('https://schema.org')
    expect(jsonLd['@type']).toBe('Event')
    expect(jsonLd.name).toBe(TEST_EVENT.title)
    expect(jsonLd.performer).toMatchObject({ '@type': 'Person', name: TEST_EVENT.artist })
    expect(jsonLd.location).toMatchObject({
      '@type': 'Place',
      name: TEST_EVENT.venueName,
    })
    expect(jsonLd.eventStatus).toBe('https://schema.org/EventScheduled')
    expect(jsonLd.eventAttendanceMode).toBe('https://schema.org/OfflineEventAttendanceMode')
  })

  // ────────────────────────────────────────────────────────
  // 4. 공연 일정 목록 검증
  // ────────────────────────────────────────────────────────
  test('공연 일정이 날짜별 그룹으로 표시된다', async ({ page }) => {
    await page.goto(EVENT_URL)

    // 섹션 제목
    await expect(page.getByRole('heading', { name: '공연 일정' })).toBeVisible()

    // 날짜 헤더 (2026-06-01 = 월요일, 2026-06-02 = 화요일)
    await expect(page.getByText('2026.06.01 (월)')).toBeVisible()
    await expect(page.getByText('2026.06.02 (화)')).toBeVisible()

    // 1회차 시간대
    await expect(page.getByText('19:00 ~ 22:00')).toBeVisible()

    // "예매하기" 버튼 (1회차 — 미매진)
    const bookingButton = page.getByRole('link', { name: '예매하기' }).first()
    await expect(bookingButton).toBeVisible()
    await expect(bookingButton).toHaveAttribute('href', `/queue/${TEST_EVENT_IDS.schedule1Id}`)
  })

  // ────────────────────────────────────────────────────────
  // 5. 매진 회차 처리 검증
  // ────────────────────────────────────────────────────────
  test('매진 회차는 "매진" 배지와 비활성화된 버튼으로 표시된다', async ({ page }) => {
    await page.goto(EVENT_URL)

    // "매진" 배지 표시
    await expect(page.getByText('매진').first()).toBeVisible()

    // 2회차 시간대
    await expect(page.getByText('17:00 ~ 20:00')).toBeVisible()

    // 비활성화된 버튼 — 매진 버튼은 disabled
    const soldOutButtons = page.getByRole('button', { name: '매진' })
    await expect(soldOutButtons.first()).toBeDisabled()
  })

  // ────────────────────────────────────────────────────────
  // 6. 좌석 등급 및 가격 표시 검증
  // ────────────────────────────────────────────────────────
  test('좌석 등급별 이름과 가격이 올바르게 표시된다', async ({ page }) => {
    await page.goto(EVENT_URL)

    // 좌석 정보 섹션
    await expect(page.getByRole('heading', { name: '좌석 정보' })).toBeVisible()

    // VIP 등급 및 가격
    await expect(page.getByText('VIP')).toBeVisible()
    await expect(page.getByText('150,000원')).toBeVisible()

    // S 등급 및 가격
    await expect(page.getByText(/^S$/).first()).toBeVisible()
    await expect(page.getByText('120,000원')).toBeVisible()
  })

  // ────────────────────────────────────────────────────────
  // 7. 404 처리 검증
  // ────────────────────────────────────────────────────────
  test('존재하지 않는 공연 ID로 접속하면 not-found 페이지가 렌더링된다', async ({ page }) => {
    await page.goto(NOT_FOUND_URL)

    await expect(page.locator('h1')).toHaveText('404')
    await expect(page.locator('main')).toContainText('페이지를 찾을 수 없습니다')
  })
})
