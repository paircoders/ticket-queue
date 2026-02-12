import axios from 'axios'
import MockAdapter from 'axios-mock-adapter'
import { apiClient } from '../axios'
import { useAuthStore } from '@/stores/auth-store'
import { useQueueStore } from '@/stores/queue-store'
import { handleApiError } from '../error-handler'
import { redirectTo } from '@/lib/navigation'

jest.mock('../error-handler')
jest.mock('@/lib/navigation')
jest.mock('@/lib/auth/cookies', () => ({
  setAccessTokenCookie: jest.fn().mockResolvedValue(undefined),
  setRefreshTokenCookie: jest.fn().mockResolvedValue(undefined),
  clearAllTokenCookies: jest.fn().mockResolvedValue(undefined),
}))

const mockRedirectTo = redirectTo as jest.MockedFunction<typeof redirectTo>

// Deferred Promise helper
function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

// Flush all pending promises
const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('Axios Interceptors', () => {
  let mock: MockAdapter
  let mockAxiosPost: jest.SpyInstance

  beforeEach(() => {
    jest.clearAllMocks()

    // Reset stores
    useAuthStore.getState().logout()
    useQueueStore.getState().clearQueue()

    // MockAdapter for apiClient (interceptors run correctly)
    mock = new MockAdapter(apiClient)

    // Spy on raw axios.post (used in refresh API)
    mockAxiosPost = jest.spyOn(axios, 'post')
  })

  afterEach(() => {
    mock.restore()
    jest.restoreAllMocks()
  })

  describe('Request Interceptor', () => {
    it('adds Authorization header when accessToken exists', async () => {
      useAuthStore.getState().setAccessToken('access-token')

      mock.onGet('/test').reply((config) => {
        expect(config.headers?.Authorization).toBe('Bearer access-token')
        return [200, { result: 'ok' }]
      })

      await apiClient.get('/test')
    })

    it('adds X-Queue-Token header when queueToken exists', async () => {
      useQueueStore.getState().setQueueToken('queue-token-123', 'schedule-1')

      mock.onGet('/test').reply((config) => {
        expect(config.headers?.['X-Queue-Token']).toBe('queue-token-123')
        return [200, { result: 'ok' }]
      })

      await apiClient.get('/test')
    })

    it('does not add headers when tokens are missing', async () => {
      mock.onGet('/test').reply((config) => {
        expect(config.headers?.Authorization).toBeUndefined()
        expect(config.headers?.['X-Queue-Token']).toBeUndefined()
        return [200, { result: 'ok' }]
      })

      await apiClient.get('/test')
    })

    it('adds both headers when both tokens exist', async () => {
      useAuthStore.getState().setAccessToken('access-token')
      useQueueStore.getState().setQueueToken('queue-token-123', 'schedule-1')

      mock.onGet('/test').reply((config) => {
        expect(config.headers?.Authorization).toBe('Bearer access-token')
        expect(config.headers?.['X-Queue-Token']).toBe('queue-token-123')
        return [200, { result: 'ok' }]
      })

      await apiClient.get('/test')
    })
  })

  describe('Response Interceptor - Token Refresh', () => {
    it('refreshes token on 401 and retries original request', async () => {
      useAuthStore.getState().setAccessToken('old-access')

      // First call: 401, second call (retry): success
      let callCount = 0
      mock.onGet('/test').reply(() => {
        callCount++
        if (callCount === 1) {
          return [401, { message: 'Unauthorized' }]
        }
        return [200, { result: 'ok' }]
      })

      // Mock refresh API
      mockAxiosPost.mockResolvedValue({
        data: {
          accessToken: 'new-access',
          refreshToken: 'new-refresh',
          expiresIn: 3600,
          tokenType: 'Bearer',
        },
      })

      const response = await apiClient.get('/test')

      expect(mockAxiosPost).toHaveBeenCalledWith(
        expect.stringContaining('/api/auth/refresh'),
        {},
        expect.any(Object)
      )

      expect(useAuthStore.getState().accessToken).toBe('new-access')
      expect(response.data.result).toBe('ok')
    })

    it('logs out and redirects when refresh API fails', async () => {
      useAuthStore.getState().setAccessToken('access-token')

      mock.onGet('/test').reply(401, { message: 'Unauthorized' })

      mockAxiosPost.mockRejectedValue(new Error('Refresh failed'))

      await expect(apiClient.get('/test')).rejects.toThrow()

      expect(useAuthStore.getState().accessToken).toBeNull()
      expect(mockRedirectTo).toHaveBeenCalledWith('/login')
    })

    it('does not retry when request already has _retry flag', async () => {
      useAuthStore.getState().setAccessToken('access-token')

      // First call: 401 triggers refresh + retry with _retry=true
      // Second call (retry): also 401, but _retry is set so no more refresh
      mock.onGet('/test').reply(401, { message: 'Unauthorized' })

      // Refresh succeeds, but retry also gets 401
      mockAxiosPost.mockResolvedValue({
        data: {
          accessToken: 'new-access',
          refreshToken: 'new-refresh',
          expiresIn: 3600,
          tokenType: 'Bearer',
        },
      })

      await expect(apiClient.get('/test')).rejects.toThrow()

      // Refresh was called once (first 401), but second 401 was not retried
      expect(mockAxiosPost).toHaveBeenCalledTimes(1)
      expect(handleApiError).toHaveBeenCalled()
    })

    it('handles concurrent 401s with single refresh call', async () => {
      useAuthStore.getState().setAccessToken('old-access')

      const deferred = createDeferred<any>()

      // Mock refresh to be slow (controlled by deferred)
      mockAxiosPost.mockReturnValue(deferred.promise)

      // Setup adapter: first 2 calls get 401, subsequent calls succeed
      let callCount = 0
      mock.onGet(/\/test\d/).reply(() => {
        callCount++
        if (callCount <= 2) {
          return [401, { message: 'Unauthorized' }]
        }
        return [200, { result: 'ok' }]
      })

      // Start 2 concurrent requests
      const request1 = apiClient.get('/test1')
      const request2 = apiClient.get('/test2')

      await flushPromises()

      // Resolve refresh call
      deferred.resolve({
        data: {
          accessToken: 'new-access',
          refreshToken: 'new-refresh',
          expiresIn: 3600,
          tokenType: 'Bearer',
        },
      })

      const [response1, response2] = await Promise.all([request1, request2])

      // Only 1 refresh call despite 2 concurrent 401s
      expect(mockAxiosPost).toHaveBeenCalledTimes(1)
      expect(response1.data.result).toBe('ok')
      expect(response2.data.result).toBe('ok')
    })

    it('rejects all queued requests when refresh fails', async () => {
      useAuthStore.getState().setAccessToken('old-access')

      const deferred = createDeferred<any>()

      mockAxiosPost.mockReturnValue(deferred.promise)

      mock.onGet(/\/test\d/).reply(401, { message: 'Unauthorized' })

      const request1 = apiClient.get('/test1')
      const request2 = apiClient.get('/test2')

      await flushPromises()

      // Reject refresh call
      deferred.reject(new Error('Refresh failed'))

      await expect(Promise.all([request1, request2])).rejects.toThrow()

      expect(useAuthStore.getState().accessToken).toBeNull()
      expect(mockRedirectTo).toHaveBeenCalledWith('/login')
    })
  })

  describe('Response Interceptor - Non-401 Errors', () => {
    it('calls handleApiError for 500 error', async () => {
      mock.onGet('/test').reply(500, { message: 'Server Error' })

      await expect(apiClient.get('/test')).rejects.toThrow()

      expect(handleApiError).toHaveBeenCalled()
    })

    it('rejects error without response gracefully', async () => {
      mock.onGet('/test').networkError()

      await expect(apiClient.get('/test')).rejects.toThrow()
    })
  })
})
