'use server'

import { cookies } from 'next/headers'

const isProduction = process.env.NODE_ENV === 'production'

/**
 * Access Token을 httpOnly 쿠키에 저장 (1시간)
 */
export async function setAccessTokenCookie(token: string): Promise<void> {
  const cookieStore = await cookies()

  cookieStore.set('accessToken', token, {
    httpOnly: true,
    secure: isProduction,
    sameSite: 'strict',
    maxAge: 3600, // 1시간 (초 단위)
    path: '/',
  })
}

/**
 * Refresh Token을 httpOnly 쿠키에 저장 (7일)
 */
export async function setRefreshTokenCookie(token: string): Promise<void> {
  const cookieStore = await cookies()

  cookieStore.set('refreshToken', token, {
    httpOnly: true,
    secure: isProduction,
    sameSite: 'strict',
    maxAge: 604800, // 7일 (초 단위)
    path: '/',
  })
}

/**
 * Queue Token을 httpOnly 쿠키에 저장 (10분)
 */
export async function setQueueTokenCookie(token: string): Promise<void> {
  const cookieStore = await cookies()

  cookieStore.set('queueToken', token, {
    httpOnly: true,
    secure: isProduction,
    sameSite: 'strict',
    maxAge: 600, // 10분 (초 단위)
    path: '/',
  })
}

/**
 * 모든 인증 토큰 쿠키 삭제
 */
export async function clearAllTokenCookies(): Promise<void> {
  const cookieStore = await cookies()

  cookieStore.delete('accessToken')
  cookieStore.delete('refreshToken')
  cookieStore.delete('queueToken')
}

/**
 * Access Token을 서버 사이드에서 읽기
 */
export async function getAccessTokenCookie(): Promise<string | undefined> {
  const cookieStore = await cookies()
  return cookieStore.get('accessToken')?.value
}

/**
 * Refresh Token을 서버 사이드에서 읽기
 */
export async function getRefreshTokenCookie(): Promise<string | undefined> {
  const cookieStore = await cookies()
  return cookieStore.get('refreshToken')?.value
}

/**
 * Queue Token을 서버 사이드에서 읽기
 */
export async function getQueueTokenCookie(): Promise<string | undefined> {
  const cookieStore = await cookies()
  return cookieStore.get('queueToken')?.value
}
