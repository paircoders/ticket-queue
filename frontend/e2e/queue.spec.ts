/**
 * Issue #117 — 대기열 페이지 E2E 테스트
 *
 * 테스트 전제:
 *   - Docker 인프라 실행 중 (PostgreSQL:5432, Valkey:6379)
 *   - API Gateway (8080) + User Service (8081) + Queue Service 실행 중
 *   - Playwright webServer가 자동으로 Next.js dev 서버 실행
 *
 * 모킹 전략:
 *   - API Gateway 응답을 page.route()로 인터셉트하여 대기열 상태를 제어
 *   - 인증 쿠키는 generateTestAccessToken()으로 유효 JWT를 생성 후 직접 주입
 */

import { test, expect, type Page, type Route } from '@playwright/test'
import { generateTestAccessToken, generateTestQueueToken } from './helpers/generate-test-token'

const SCHEDULE_ID = 'test-schedule-001'
const QUEUE_PAGE = `/queue/${SCHEDULE_ID}`

test.describe('대기열 페이지', () => {
  let validAccessToken: string
  let validQueueToken: string

  test.beforeAll(async () => {
    validAccessToken = await generateTestAccessToken()
    validQueueToken = await generateTestQueueToken({ scheduleId: SCHEDULE_ID })
  })

  test.beforeEach(async ({ page }) => {
    await setupApiCors(page)

    await page.context().addCookies([
      {
        name: 'accessToken',
        value: validAccessToken,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: false,
      },
    ])
  })

  // ────────────────────────────────────────────────────────
  // 테스트 1: WAITING 상태 렌더링
  // ────────────────────────────────────────────────────────
  test('WAITING 상태에서 대기 순서, 예상 시간, 진행률, 타이머, 나가기 버튼이 표시된다', async ({
    page,
  }) => {
    await mockQueueStatus(page, {
      status: 'WAITING',
      rank: 150,
      estimatedWaitTime: 300,
      token: null,
    })

    await page.goto(QUEUE_PAGE)

    // 대기 순서 (QueuePosition: "150번째" 전체 텍스트로 정확히 매칭)
    await expect(page.getByText('150번째')).toBeVisible({ timeout: 10000 })

    // 예상 시간 (300초 = 5분)
    await expect(page.getByText('약 5분')).toBeVisible()

    // 진행률 바
    await expect(page.getByRole('progressbar')).toBeVisible()

    // 타이머 (MM:SS 형식)
    await expect(page.locator('p.font-mono')).toBeVisible()

    // 대기열 나가기 버튼
    await expect(page.getByRole('button', { name: '대기열 나가기' })).toBeVisible()
  })

  // ────────────────────────────────────────────────────────
  // 테스트 2: 폴링으로 position 갱신
  // ────────────────────────────────────────────────────────
  test('폴링으로 대기 순서가 갱신된다', async ({ page }) => {
    let callCount = 0

    await page.route('**/queue/status**', async (route) => {
      callCount++
      const rank = callCount === 1 ? 150 : 100
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'WAITING',
          rank,
          estimatedWaitTime: callCount === 1 ? 300 : 200,
          token: null,
        }),
      })
    })

    await page.goto(QUEUE_PAGE)

    // 첫 번째 폴링: 150번
    await expect(page.getByText('150번째')).toBeVisible({ timeout: 10000 })

    // 두 번째 폴링 대기 (5초 간격)
    await page.waitForTimeout(6000)
    await expect(page.getByText('100번째')).toBeVisible()
  })

  // ────────────────────────────────────────────────────────
  // 테스트 3: ACTIVE 시 자동 리디렉트 + X-Queue-Token 헤더 검증
  // ────────────────────────────────────────────────────────
  test('ACTIVE 상태가 되면 좌석 선택 페이지로 자동 리디렉트된다', async ({ page }) => {
    let callCount = 0

    await page.route('**/queue/status**', async (route) => {
      callCount++
      if (callCount === 1) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 'WAITING',
            rank: 5,
            estimatedWaitTime: 30,
            token: null,
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            status: 'ACTIVE',
            rank: 0,
            estimatedWaitTime: 0,
            token: validQueueToken,
          }),
        })
      }
    })

    // reservation 엔드포인트 mock (X-Queue-Token 헤더 검증용)
    await page.route('**/reservations/**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      })
    })

    // 리디렉트 후 첫 reservation API 요청 캡처
    const reservationRequestPromise = page.waitForRequest(
      (req) => req.url().includes('/reservations/'),
      { timeout: 15000 }
    )

    await page.goto(QUEUE_PAGE)

    // 첫 폴링 WAITING 확인
    await expect(page.getByText('5번째')).toBeVisible({ timeout: 10000 })

    // 두 번째 폴링 후 리디렉트 확인 (5초 + 처리 시간)
    await expect(page).toHaveURL(`/reservation/${SCHEDULE_ID}`, { timeout: 15000 })

    // X-Queue-Token 헤더 검증 (reservation 요청에 포함되는지 확인)
    const reservationRequest = await reservationRequestPromise
    expect(reservationRequest.headers()['x-queue-token']).toBe(validQueueToken)

    // queueToken 쿠키가 저장됐는지 검증
    const cookies = await page.context().cookies()
    const queueTokenCookie = cookies.find((c) => c.name === 'queueToken')
    expect(queueTokenCookie).toBeDefined()
    expect(queueTokenCookie?.value).toBe(validQueueToken)
  })

  // ────────────────────────────────────────────────────────
  // 테스트 4: 대기열 나가기 + 확인 다이얼로그
  // ────────────────────────────────────────────────────────
  test('대기열 나가기 버튼 클릭 시 확인 다이얼로그가 표시되고 확인 시 DELETE 호출 후 홈으로 이동한다', async ({
    page,
  }) => {
    await mockQueueStatus(page, {
      status: 'WAITING',
      rank: 50,
      estimatedWaitTime: 150,
      token: null,
    })

    let deleteCallCount = 0
    await page.route('**/queue/leave**', async (route) => {
      if (route.request().method() === 'DELETE') {
        // scheduleId 쿼리 파라미터 검증
        const url = new URL(route.request().url())
        expect(url.searchParams.get('scheduleId')).toBe(SCHEDULE_ID)
        // Authorization 헤더 검증
        const authHeader = route.request().headers()['authorization']
        expect(authHeader).toMatch(/^Bearer /)
        deleteCallCount++
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ message: '대기열에서 이탈했습니다.' }),
        })
      } else {
        await route.continue()
      }
    })

    await page.goto(QUEUE_PAGE)
    await expect(page.getByRole('button', { name: '대기열 나가기' })).toBeVisible({ timeout: 10000 })

    // 다이얼로그 열기
    await page.getByRole('button', { name: '대기열 나가기' }).click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5000 })
    await expect(page.getByText('대기열에서 나가시겠습니까?')).toBeVisible()

    // 취소 버튼 테스트
    await page.getByRole('button', { name: '취소' }).click()
    await expect(page.getByText('대기열에서 나가시겠습니까?')).not.toBeVisible()
    expect(deleteCallCount).toBe(0)

    // 다시 열고 나가기 확인
    await page.getByRole('button', { name: '대기열 나가기' }).click()
    await page.getByRole('button', { name: '나가기', exact: true }).click()

    // DELETE 호출 확인 + 홈으로 이동
    await expect(page).toHaveURL('/', { timeout: 10000 })
    expect(deleteCallCount).toBe(1)
  })

  // ────────────────────────────────────────────────────────
  // 테스트 5: NOT_IN_QUEUE 에러 상태
  // ────────────────────────────────────────────────────────
  test('NOT_IN_QUEUE 에러 시 에러 UI와 네비게이션 버튼이 표시된다', async ({ page }) => {
    await page.route('**/queue/status**', async (route) => {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'NOT_IN_QUEUE',
          message: '대기열에 등록되어 있지 않습니다.',
          timestamp: new Date().toISOString(),
          traceId: 'test-trace-id',
        }),
      })
    })

    await page.goto(QUEUE_PAGE)

    await expect(page.getByText('대기열에 없습니다')).toBeVisible({ timeout: 10000 })
    await expect(page.getByText('대기열에 등록되어 있지 않습니다. 공연 페이지로')).toBeVisible()
    await expect(page.getByRole('button', { name: '홈으로 이동' })).toBeVisible()
    await expect(page.getByRole('button', { name: '뒤로 가기' })).toBeVisible()
  })

  // ────────────────────────────────────────────────────────
  // 테스트 6: 타이머 경고 색상
  // ────────────────────────────────────────────────────────
  test('남은 시간 1분 미만 시 타이머가 빨간색으로 표시된다', async ({ page }) => {
    await mockQueueStatus(page, {
      status: 'WAITING',
      rank: 10,
      estimatedWaitTime: 50,
      token: null,
    })

    // enteredAt을 9분 30초 전으로 조작 (localStorage persist)
    const nineMinutesThirtySecondsAgo = Date.now() - 9 * 60 * 1000 - 30 * 1000

    // 먼저 페이지를 로드해 localStorage 도메인 컨텍스트를 활성화
    await page.goto(QUEUE_PAGE)
    await expect(page.getByText('10번째')).toBeVisible({ timeout: 10000 })

    // localStorage에 enteredAt 조작 (persist 미들웨어 키: 'queue-storage')
    await page.evaluate((enteredAt) => {
      const stored = JSON.parse(localStorage.getItem('queue-storage') ?? '{"state":{}}')
      stored.state = { ...(stored.state ?? {}), enteredAt }
      localStorage.setItem('queue-storage', JSON.stringify(stored))
    }, nineMinutesThirtySecondsAgo)

    // 페이지 새로고침으로 persist 값 로드
    await page.reload()
    await expect(page.getByText('10번째')).toBeVisible({ timeout: 10000 })

    // 빨간색 타이머 확인 (1분 미만)
    const timer = page.locator('p.font-mono')
    await expect(timer).toBeVisible()
    await expect(timer).toHaveClass(/text-red-600/)
  })

  // ────────────────────────────────────────────────────────
  // 테스트 7: TTL 만료 시 홈으로 리디렉트
  // ────────────────────────────────────────────────────────
  test('TTL이 만료되면 홈으로 리디렉트된다', async ({ page }) => {
    await mockQueueStatus(page, {
      status: 'WAITING',
      rank: 10,
      estimatedWaitTime: 50,
      token: null,
    })

    // 먼저 페이지를 로드해 localStorage 도메인 컨텍스트를 활성화
    await page.goto(QUEUE_PAGE)
    await expect(page.getByText('10번째')).toBeVisible({ timeout: 10000 })

    // enteredAt을 11분 전으로 조작 (이미 만료된 상태)
    const elevenMinutesAgo = Date.now() - 11 * 60 * 1000
    await page.evaluate((enteredAt) => {
      const stored = JSON.parse(localStorage.getItem('queue-storage') ?? '{"state":{}}')
      stored.state = { ...(stored.state ?? {}), enteredAt }
      localStorage.setItem('queue-storage', JSON.stringify(stored))
    }, elevenMinutesAgo)

    // 페이지 새로고침으로 persist 값 로드
    await page.reload()
    await expect(page.getByText('10번째')).toBeVisible({ timeout: 10000 })

    // 타이머가 즉시 만료 → onExpire 콜백 → 홈으로 리디렉트
    await expect(page).not.toHaveURL(QUEUE_PAGE, { timeout: 5000 })
  })
})

// ────────────────────────────────────────────────────────
// 헬퍼 함수
// ────────────────────────────────────────────────────────

interface QueueStatusMock {
  status: 'WAITING' | 'ACTIVE'
  rank: number
  estimatedWaitTime: number
  token: string | null
}

async function mockQueueStatus(page: Page, data: QueueStatusMock): Promise<void> {
  await page.route('**/queue/status**', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(data),
    })
  })
}

async function setupApiCors(page: Page): Promise<void> {
  await page.route('http://localhost:8080/**', async (route: Route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({
        status: 200,
        headers: {
          'Access-Control-Allow-Origin': 'http://localhost:3000',
          'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
          'Access-Control-Allow-Headers': 'Authorization,Content-Type,X-Queue-Token',
          'Access-Control-Allow-Credentials': 'true',
        },
      })
      return
    }

    const headers = { ...route.request().headers() }
    delete headers['origin']
    delete headers['referer']

    const response = await route.fetch({ headers })
    await route.fulfill({
      response,
      headers: {
        ...response.headers(),
        'Access-Control-Allow-Origin': 'http://localhost:3000',
        'Access-Control-Allow-Credentials': 'true',
      },
    })
  })
}
