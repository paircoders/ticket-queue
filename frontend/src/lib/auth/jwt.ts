/**
 * JWT 검증 유틸리티
 * Edge Runtime 호환 (미들웨어에서 사용)
 * jose 패키지 기반 JWT 검증
 */

import { jwtVerify, type JWTPayload as JoseJWTPayload } from 'jose'

/**
 * Access Token Payload 타입
 */
export interface JWTPayload extends JoseJWTPayload {
  userId: string
  email: string
  iat?: number
  exp?: number
}

/**
 * Queue Token Payload 타입
 */
export interface QueueTokenPayload extends JoseJWTPayload {
  userId: string
  scheduleId: string
  issuedAt: string
  iat?: number
  exp?: number
}

/**
 * Access Token 검증
 * @param token - JWT Access Token
 * @returns 검증된 Payload
 * @throws 검증 실패 시 에러
 */
export async function verifyAccessToken(token: string): Promise<JWTPayload> {
  const secret = process.env.JWT_SECRET

  if (!secret) {
    throw new Error('JWT_SECRET 환경변수가 설정되지 않았습니다.')
  }

  const secretKey = new TextEncoder().encode(secret)

  try {
    const { payload } = await jwtVerify(token, secretKey)

    // 타입 안전성 검증
    if (
      typeof payload.userId !== 'string' ||
      typeof payload.email !== 'string'
    ) {
      throw new Error('Invalid Access Token payload structure')
    }

    return payload as JWTPayload
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Invalid or expired Access Token: ${error.message}`)
    }
    throw new Error('Invalid or expired Access Token')
  }
}

/**
 * Queue Token 검증
 * @param token - JWT Queue Token
 * @returns 검증된 Payload
 * @throws 검증 실패 시 에러
 */
export async function verifyQueueToken(
  token: string,
): Promise<QueueTokenPayload> {
  const secret = process.env.QUEUE_TOKEN_SECRET

  if (!secret) {
    throw new Error('QUEUE_TOKEN_SECRET 환경변수가 설정되지 않았습니다.')
  }

  const secretKey = new TextEncoder().encode(secret)

  try {
    const { payload } = await jwtVerify(token, secretKey)

    // 타입 안전성 검증
    if (
      typeof payload.userId !== 'string' ||
      typeof payload.scheduleId !== 'string' ||
      typeof payload.issuedAt !== 'string'
    ) {
      throw new Error('Invalid Queue Token payload structure')
    }

    return payload as QueueTokenPayload
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Invalid or expired Queue Token: ${error.message}`)
    }
    throw new Error('Invalid or expired Queue Token')
  }
}
