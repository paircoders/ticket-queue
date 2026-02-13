import { NextRequest, NextResponse } from 'next/server'
import axios from 'axios'
import type { RefreshResponse, RefreshTokenRequest } from '@/types/auth'

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL

export async function POST(request: NextRequest) {
  try {
    // 1. httpOnly 쿠키에서 refreshToken 읽기
    const refreshToken = request.cookies.get('refreshToken')?.value

    if (!refreshToken) {
      return NextResponse.json(
        { error: 'Refresh token not found' },
        { status: 401 }
      )
    }

    // 2. 백엔드 POST /auth/refresh 호출
    const response = await axios.post<RefreshResponse>(
      `${API_BASE_URL}/auth/refresh`,
      {
        refreshToken,
      } as RefreshTokenRequest
    )

    const { accessToken, refreshToken: newRefreshToken, expiresIn } = response.data

    // 3. 성공 시 응답 생성 및 쿠키 설정
    const nextResponse = NextResponse.json(
      { accessToken },
      { status: 200 }
    )

    // Set-Cookie: accessToken (httpOnly, Secure in production)
    nextResponse.cookies.set('accessToken', accessToken, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'strict',
      maxAge: expiresIn || 3600, // 기본 1시간
      path: '/',
    })

    // Set-Cookie: refreshToken (RTR 지원 - 새 refreshToken 설정)
    nextResponse.cookies.set('refreshToken', newRefreshToken, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'strict',
      maxAge: 604800, // 7일
      path: '/',
    })

    return nextResponse
  } catch (_error) {
    // 4. 실패 시 모든 토큰 쿠키 삭제
    const errorResponse = NextResponse.json(
      { error: 'Token refresh failed' },
      { status: 401 }
    )

    // 토큰 쿠키 삭제 (maxAge: 0)
    errorResponse.cookies.set('accessToken', '', {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'strict',
      maxAge: 0,
      path: '/',
    })

    errorResponse.cookies.set('refreshToken', '', {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'strict',
      maxAge: 0,
      path: '/',
    })

    return errorResponse
  }
}
