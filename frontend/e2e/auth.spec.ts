/**
 * PR #180 수동 테스트 체크리스트 E2E 자동화
 *
 * 테스트 전제:
 *   - Docker 인프라 실행 중 (PostgreSQL:5432, Valkey:6379)
 *   - API Gateway (8080) + User Service (8081) 실행 중
 *   - Playwright webServer가 자동으로 Next.js dev 서버 실행
 *
 * reCAPTCHA 전략:
 *   - Test 1 (UI 확인): 실제 Google reCAPTCHA iframe 로딩 확인
 *   - Test 3~5 (로그인 흐름): page.route()로 recaptcha/api.js 인터셉트 →
 *     mock grecaptcha가 즉시 onChange('e2e-test-token') 호출
 *     (백엔드는 Google 테스트 시크릿으로 검증하므로 통과)
 */

import { test, expect, type Page } from '@playwright/test'
import { seedTestUser, TEST_USER } from './helpers/seed-user'

// 이 파일의 테스트는 순차 실행 (로그인 상태 공유 방지)
test.describe.configure({ mode: 'serial' })

test.describe('PR #180 — 로그인 페이지 및 인증 흐름', () => {
  test.beforeAll(async () => {
    // DB에 테스트 유저 삽입 (이미 있으면 스킵)
    await seedTestUser()
  })

  // ────────────────────────────────────────────────────────
  // 체크리스트 1: 로그인 페이지 UI 확인
  // ────────────────────────────────────────────────────────
  test('로그인 페이지에 이메일/비밀번호/reCAPTCHA가 표시된다', async ({ page }) => {
    await page.goto('/login')

    // 페이지 제목 (CardTitle은 div로 렌더링, data-slot 속성 사용)
    await expect(page.locator('[data-slot="card-title"]')).toHaveText('로그인')
    await expect(page.getByText('이메일과 비밀번호를 입력해주세요.')).toBeVisible()

    // 이메일 입력 필드
    const emailInput = page.getByRole('textbox', { name: '이메일' })
    await expect(emailInput).toBeVisible()
    await expect(emailInput).toHaveAttribute('type', 'email')

    // 비밀번호 입력 필드
    await expect(page.locator('input[type="password"]')).toBeVisible()

    // reCAPTCHA iframe (실제 Google 위젯 로딩 확인)
    const recaptchaFrame = page.frameLocator('iframe[title="reCAPTCHA"]')
    await expect(recaptchaFrame.locator('.recaptcha-checkbox-border')).toBeVisible({
      timeout: 10000,
    })

    // 로그인 버튼
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible()

    // 회원가입 링크
    await expect(page.getByRole('link', { name: '회원가입' })).toBeVisible()
  })

  // ────────────────────────────────────────────────────────
  // 체크리스트 2: 유효성 검증 에러 메시지
  // ────────────────────────────────────────────────────────
  test('잘못된 입력 시 유효성 에러 메시지가 표시된다', async ({ page }) => {
    await page.goto('/login')

    // (a) 빈 폼 제출
    await page.getByRole('button', { name: '로그인' }).click()

    // role="alert" 요소로 Zod 에러 메시지를 정확히 선택
    await expect(
      page.getByRole('alert').filter({ hasText: '올바른 이메일 형식을 입력해주세요.' }),
    ).toBeVisible()
    await expect(
      page.getByRole('alert').filter({ hasText: '비밀번호를 입력해주세요.' }),
    ).toBeVisible()

    // (b) 잘못된 이메일 형식 입력
    await page.getByRole('textbox', { name: '이메일' }).fill('not-an-email')
    await page.getByRole('button', { name: '로그인' }).click()

    await expect(
      page.getByRole('alert').filter({ hasText: '올바른 이메일 형식을 입력해주세요.' }),
    ).toBeVisible()
  })

  // ────────────────────────────────────────────────────────
  // 체크리스트 3: 로그인 성공 → 홈 리디렉트
  // ────────────────────────────────────────────────────────
  test('올바른 입력으로 로그인 성공 시 홈으로 리디렉트된다', async ({ page }) => {
    await setupRecaptchaMock(page)
    await page.goto('/login')

    await fillLoginForm(page, TEST_USER.email, TEST_USER.password)

    // reCAPTCHA mock이 자동으로 onChange를 호출하므로 별도 클릭 불필요
    // 토큰이 설정될 시간 확보
    await page.waitForTimeout(300)

    await page.getByRole('button', { name: '로그인' }).click()

    // 홈("/")으로 리디렉트 확인
    await expect(page).toHaveURL('/', { timeout: 15000 })
  })

  // ────────────────────────────────────────────────────────
  // 체크리스트 4: 이미 인증된 사용자가 /login 접근 시 홈으로 리디렉트
  // ────────────────────────────────────────────────────────
  test('이미 인증된 사용자가 /login 접근 시 홈으로 리디렉트된다', async ({ page }) => {
    await setupRecaptchaMock(page)

    // 1단계: 로그인 → 홈(/)으로 이동
    await page.goto('/login')
    await fillLoginForm(page, TEST_USER.email, TEST_USER.password)
    await page.waitForTimeout(300)
    await page.getByRole('button', { name: '로그인' }).click()
    await expect(page).toHaveURL('/', { timeout: 15000 })

    // 2단계: /login 재방문 (full reload) — onRehydrateStorage가 user 존재 여부로 isAuthenticated 복원
    await page.goto('/login')

    // LoginForm useEffect: if (isAuthenticated) → router.replace('/')
    await expect(page).toHaveURL('/', { timeout: 5000 })
  })

  // ────────────────────────────────────────────────────────
  // 체크리스트 5: returnUrl 복귀 (인증 필요 페이지 → 로그인 → 원래 페이지)
  // ────────────────────────────────────────────────────────
  test('인증 필요 페이지 접근 후 로그인하면 원래 페이지로 복귀한다', async ({ page }) => {
    // serial 모드는 브라우저 컨텍스트를 공유하므로, 이전 테스트의 accessToken 쿠키 제거
    await page.context().clearCookies()
    await page.evaluate(() => localStorage.clear()).catch(() => {})

    await setupRecaptchaMock(page)

    // 인증이 필요한 경로 접근 (미들웨어가 /login?returnUrl= 으로 리디렉트)
    await page.goto('/queue')

    // /login?returnUrl=/queue 로 리디렉트되었는지 확인
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fqueue/, { timeout: 5000 })

    // 로그인 진행
    await fillLoginForm(page, TEST_USER.email, TEST_USER.password)
    await page.waitForTimeout(300)
    await page.getByRole('button', { name: '로그인' }).click()

    // 로그인 성공 후 원래 경로(/queue)로 복귀
    await expect(page).toHaveURL('/queue', { timeout: 15000 })
  })
})

// ────────────────────────────────────────────────────────
// 헬퍼 함수
// ────────────────────────────────────────────────────────

/**
 * reCAPTCHA 스크립트를 인터셉트하고, API Gateway CORS 헤더를 주입합니다.
 *
 * [reCAPTCHA Mock]
 * - URL의 `?onload=콜백명` 파라미터를 추출, mock grecaptcha 초기화 후 콜백 호출
 * - react-async-script는 script.onload 이후 window[callbackName]()을 기대하므로
 *   setTimeout(..., 0)으로 지연하여 mapEntry.loaded=true 이후에 호출
 *
 * [CORS 우회]
 * - API Gateway allowed-origins에 localhost:3000이 없음
 * - route.fetch()로 Node.js 레벨에서 백엔드 호출 (CORS 미적용)
 * - 응답에 CORS 헤더를 추가해서 route.fulfill()로 브라우저에 반환
 */
async function setupRecaptchaMock(page: Page): Promise<void> {
  // 1. reCAPTCHA 스크립트 mock
  await page.route('**/recaptcha/api.js**', async (route) => {
    const url = new URL(route.request().url())
    const callbackName = url.searchParams.get('onload') ?? 'onloadcallback'

    await route.fulfill({
      contentType: 'application/javascript',
      body: `(function(){
        window.grecaptcha={
          ready:function(fn){if(typeof fn==='function')fn();},
          render:function(el,p){
            setTimeout(function(){if(p&&typeof p.callback==='function')p.callback('e2e-test-token');},50);
            return 0;
          },
          reset:function(){},
          getResponse:function(){return 'e2e-test-token';},
          execute:function(){return Promise.resolve('e2e-test-token');}
        };
        // script.onload 이후 callbackName 호출로 mapEntry.loaded=true 보장
        setTimeout(function(){
          if(typeof window['${callbackName}']==='function')window['${callbackName}']();
        },0);
      })();`,
    })
  })

  // 2. API Gateway CORS 헤더 주입 (allowed-origins에 localhost:3000 미포함 우회)
  await page.route('http://localhost:8080/**', async (route) => {
    // OPTIONS preflight 요청은 즉시 200 응답
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

    // Origin 헤더를 API Gateway가 허용하는 값으로 교체
    // (route.fetch()가 localhost:3000을 그대로 전달하면 서버 CORS 필터가 403 반환)
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

/** 로그인 폼에 이메일/비밀번호를 입력합니다 */
async function fillLoginForm(page: Page, email: string, password: string): Promise<void> {
  await page.getByRole('textbox', { name: '이메일' }).fill(email)
  await page.locator('input[type="password"]').fill(password)
}
