import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth-store'
import { useQueueStore } from '@/stores/queue-store'
import { handleApiError } from './error-handler'
import { redirectTo } from '@/lib/navigation'
import type { RefreshResponse } from '@/types/auth'

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

  if (queueToken) {
    config.headers['X-Queue-Token'] = queueToken
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

      const { refreshToken, logout, setAccessToken, setTokens } =
        useAuthStore.getState()

      if (!refreshToken) {
        isRefreshing = false
        processQueue(error, null)
        logout()
        redirectTo('/login')
        return Promise.reject(error)
      }

      try {
        // Use plain axios to avoid infinite loop
        const { data } = await axios.post<RefreshResponse>(
          `${process.env.NEXT_PUBLIC_API_BASE_URL}/auth/refresh`,
          { refreshToken },
          {
            headers: { 'Content-Type': 'application/json' },
          }
        )

        setAccessToken(data.accessToken)
        setTokens(data.accessToken, data.refreshToken)

        processQueue(null, data.accessToken)

        originalRequest.headers.Authorization = `Bearer ${data.accessToken}`
        return apiClient(originalRequest)
      } catch (refreshError) {
        processQueue(refreshError, null)
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
