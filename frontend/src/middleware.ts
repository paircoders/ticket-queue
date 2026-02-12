/**
 * Next.js Middleware - 인증 및 Queue Token 검증
 *
 * 경로 분류:
 * 1. PUBLIC_PATHS: 인증 불필요 (통과)
 * 2. QUEUE_TOKEN_REQUIRED_PATHS: 인증 + Queue Token 필수
 * 3. 나머지: 인증만 필수
 *
 * 검증 순서:
 * 1. 정적 자원/API 경로 → 스킵 (matcher로 처리)
 * 2. PUBLIC_PATHS → 통과
 * 3. accessToken 쿠키 확인 → 없으면 /login 리디렉트
 * 4. verifyAccessToken() 호출 → 실패 시 /login 리디렉트
 * 5. QUEUE_TOKEN_REQUIRED_PATHS 체크
 *    - /reservation 또는 /payment 경로면 queueToken 확인
 *    - 단, /payment/complete는 예외 (Queue Token 불필요)
 *    - queueToken 없으면 /queue 리디렉트
 *    - verifyQueueToken() 호출 → 실패 시 /queue 리디렉트
 * 6. 통과 → NextResponse.next()
 */

import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'
import { verifyAccessToken, verifyQueueToken } from '@/lib/auth/jwt'

// 공개 경로 (인증 불필요)
const PUBLIC_PATHS = ['/', '/login', '/signup', '/events']

// Queue Token 필수 경로
const QUEUE_TOKEN_REQUIRED_PATHS = ['/reservation', '/payment']

export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl

  // 1. 공개 경로는 통과
  if (PUBLIC_PATHS.some((path) => pathname.startsWith(path))) {
    return NextResponse.next()
  }

  // 2. Access Token 확인
  const accessToken = request.cookies.get('accessToken')?.value

  if (!accessToken) {
    // Access Token 없음 → /login 리디렉트
    return NextResponse.redirect(new URL('/login', request.url))
  }

  // 3. Access Token 검증
  try {
    await verifyAccessToken(accessToken)
  } catch {
    // Access Token 만료 또는 유효하지 않음 → /login 리디렉트
    return NextResponse.redirect(new URL('/login', request.url))
  }

  // 4. Queue Token 필요 경로 체크
  const isQueueTokenRequired = QUEUE_TOKEN_REQUIRED_PATHS.some((path) =>
    pathname.startsWith(path),
  )

  if (isQueueTokenRequired) {
    // /payment/complete는 Queue Token 불필요 (이미 결제 완료 상태)
    if (pathname === '/payment/complete') {
      return NextResponse.next()
    }

    // Queue Token 확인
    const queueToken = request.cookies.get('queueToken')?.value

    if (!queueToken) {
      // Queue Token 없음 → /queue 리디렉트
      return NextResponse.redirect(new URL('/queue', request.url))
    }

    // Queue Token 검증
    try {
      await verifyQueueToken(queueToken)
    } catch {
      // Queue Token 만료 또는 유효하지 않음 → /queue 리디렉트
      return NextResponse.redirect(new URL('/queue', request.url))
    }
  }

  // 5. 모든 검증 통과
  return NextResponse.next()
}

// 미들웨어가 실행될 경로 패턴 설정
export const config = {
  matcher: [
    /*
     * 다음 경로를 제외한 모든 경로에서 실행:
     * - api (API routes)
     * - _next/static (static files)
     * - _next/image (image optimization files)
     * - favicon.ico (favicon file)
     */
    '/((?!api|_next/static|_next/image|favicon.ico).*)',
  ],
}
