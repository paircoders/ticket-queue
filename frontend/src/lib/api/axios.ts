import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth-store'
import { useQueueStore } from '@/stores/queue-store'
import { handleApiError } from './error-handler'
import { redirectTo } from '@/lib/navigation'
import type { RefreshResponse } from '@/types/auth'
import { ERROR_CODES } from '@/lib/api/error-codes'

function getCookieValue(name: string): string | null {
  if (typeof document === 'undefined') return null
  const match = document.cookie
    .split('; ')
    .find((row) => row.startsWith(`${name}=`))
  return match ? decodeURIComponent(match.split('=')[1]) : null
}

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

export const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request Interceptor
apiClient.interceptors.request.use((config) => {
  const { accessToken } = useAuthStore.getState()
  const { queueToken } = useQueueStore.getState()

  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }

  const resolvedQueueToken = queueToken ?? getCookieValue('queueToken')
  if (resolvedQueueToken) {
    config.headers['X-Queue-Token'] = resolvedQueueToken
  }

  return config
})

// Response Interceptor - concurrent 401 handling
let isRefreshing = false
let failedQueue: {
  resolve: (token: string) => void
  reject: (error: unknown) => void
}[] = []

function processQueue(error: unknown, token: string | null): void {
  failedQueue.forEach(({ resolve, reject }) => {
    if (token) {
      resolve(token)
    } else {
      reject(error)
    }
  })
  failedQueue = []
}

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as RetryableRequestConfig | undefined

    if (!originalRequest) {
      return Promise.reject(error)
    }

    // Handle 401 - Queue Token 만료 처리 (Access Token 갱신보다 먼저 확인)
    if (error.response?.status === 401) {
      const errorCode = (error.response?.data as { code?: string } | undefined)?.code

      if (errorCode === ERROR_CODES.QUEUE_TOKEN_EXPIRED || errorCode === ERROR_CODES.QUEUE_TOKEN_INVALID) {
        if (typeof window !== 'undefined') {
          const pathMatch = window.location.pathname.match(/\/reservation\/([^/]+)/)
          const scheduleId = pathMatch?.[1]
          const redirectUrl = scheduleId ? `/queue/${scheduleId}` : '/'
          window.location.href = redirectUrl
        }
        return Promise.reject(error)
      }
    }

    // Handle 401 - token refresh
    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // Another refresh is in progress, queue this request
        return new Promise<string>((resolve, reject) => {
          failedQueue.push({ resolve, reject })
        }).then((token) => {
          originalRequest.headers.Authorization = `Bearer ${token}`
          return apiClient(originalRequest)
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      const { logout, setAccessToken } = useAuthStore.getState()

      try {
        // Next.js API 라우트로 변경 (refreshToken은 httpOnly 쿠키에서 자동으로 전달)
        const { data } = await axios.post<RefreshResponse>(
          '/api/auth/refresh',
          {}, // body 없음 (쿠키에서 읽음)
          {
            baseURL: '', // baseURL 오버라이드 (Next.js API 라우트)
            headers: { 'Content-Type': 'application/json' },
          }
        )

        setAccessToken(data.accessToken) // Zustand 업데이트

        // Server Action으로 쿠키 업데이트
        const { setAccessTokenCookie, setRefreshTokenCookie } =
          await import('@/lib/auth/cookies')
        await setAccessTokenCookie(data.accessToken)
        if (data.refreshToken) {
          await setRefreshTokenCookie(data.refreshToken)
        }

        processQueue(null, data.accessToken)

        originalRequest.headers.Authorization = `Bearer ${data.accessToken}`
        return apiClient(originalRequest)
      } catch (refreshError) {
        processQueue(refreshError, null)

        // Server Action으로 쿠키 삭제
        const { clearAllTokenCookies } = await import('@/lib/auth/cookies')
        await clearAllTokenCookies()

        logout()
        redirectTo('/login')
        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    // Non-401 errors - delegate to error handler
    handleApiError(error)
    return Promise.reject(error)
  }
)
