/**
 * E2E 테스트용 유효한 JWT Access Token 생성 헬퍼
 *
 * 미들웨어의 verifyAccessToken()과 동일한 방식으로 토큰을 생성:
 * - 알고리즘: HS512
 * - 시크릿: Base64 디코딩된 JWT_SECRET 바이트 (백엔드 JJWT와 동일)
 * - 페이로드: sub, email, role 포함
 */

import { SignJWT } from 'jose'
import * as dotenv from 'dotenv'
import * as path from 'path'

// .env.local에서 JWT_SECRET 로드
dotenv.config({ path: path.resolve(process.cwd(), '.env.local') })

export async function generateTestQueueToken(overrides?: {
  userId?: string
  scheduleId?: string
  expiresIn?: string
}): Promise<string> {
  const secret = process.env.QUEUE_TOKEN_SECRET
  if (!secret) {
    throw new Error('QUEUE_TOKEN_SECRET이 .env.local에 설정되어 있지 않습니다.')
  }

  const secretKey = new TextEncoder().encode(secret)

  const payload = {
    userId: overrides?.userId ?? 'e2e-test-user-id',
    scheduleId: overrides?.scheduleId ?? 'test-schedule-001',
    issuedAt: new Date().toISOString(),
  }

  return new SignJWT(payload)
    .setProtectedHeader({ alg: 'HS256' })
    .setIssuedAt()
    .setExpirationTime(overrides?.expiresIn ?? '10m')
    .sign(secretKey)
}

export async function generateTestAccessToken(overrides?: {
  sub?: string
  email?: string
  role?: string
  expiresIn?: string
}): Promise<string> {
  const secret = process.env.JWT_SECRET
  if (!secret) {
    throw new Error('JWT_SECRET이 .env.local에 설정되어 있지 않습니다.')
  }

  // 백엔드와 동일하게 Base64 디코딩
  const secretKey = Uint8Array.from(atob(secret), (c) => c.charCodeAt(0))

  const payload = {
    sub: overrides?.sub ?? 'e2e-test-user-id',
    email: overrides?.email ?? 'e2e-test@example.com',
    role: overrides?.role ?? 'USER',
    jti: 'e2e-test-jti',
  }

  return new SignJWT(payload)
    .setProtectedHeader({ alg: 'HS512' })
    .setIssuedAt()
    .setExpirationTime(overrides?.expiresIn ?? '1h')
    .sign(secretKey)
}
