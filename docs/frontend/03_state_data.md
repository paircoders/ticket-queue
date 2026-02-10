# 🔄 상태 관리 및 데이터 흐름

## 1. 상태 분류

프론트엔드 상태는 크게 3가지로 분류합니다:

| 상태 타입 | 관리 도구 | 특징 | 예시 |
|----------|----------|------|------|
| **Server State** | React Query | 서버에서 가져온 데이터, 캐싱 | 공연 목록, 좌석 정보 |
| **Client State** | Zustand | 클라이언트 전용 상태 | 테마, 사이드바 열림/닫힘 |
| **Local State** | useState | 컴포넌트 내부 상태 | 폼 입력값, 토글 상태 |

---

## 2. Server State 관리 (React Query)

### 2.1 React Query 설정

```typescript
// lib/react-query/queryClient.ts
import { QueryClient } from '@tanstack/react-query'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 2, // 실패 시 2회 재시도
      staleTime: 1000 * 60 * 5, // 5분 (데이터가 신선한 시간)
      gcTime: 1000 * 60 * 10, // 10분 (캐시 유지 시간)
      refetchOnWindowFocus: false, // 윈도우 포커스 시 재요청 비활성화
    },
    mutations: {
      retry: 0, // Mutation은 재시도 안 함
    },
  },
})
```

### 2.2 Query Keys 구조

**Query Key 규칙**: 계층적 배열 구조로 관리

```typescript
// lib/react-query/queryKeys.ts
export const queryKeys = {
  events: {
    all: ['events'] as const,
    list: (params: EventListParams) => ['events', 'list', params] as const,
    detail: (id: string) => ['events', 'detail', id] as const,
    seats: (scheduleId: string) => ['events', 'seats', scheduleId] as const,
  },
  queue: {
    status: (scheduleId: string) => ['queue', 'status', scheduleId] as const,
  },
  reservations: {
    all: ['reservations'] as const,
    list: () => ['reservations', 'list'] as const,
    detail: (id: string) => ['reservations', 'detail', id] as const,
  },
  user: {
    profile: () => ['user', 'profile'] as const,
  },
}
```

### 2.3 주요 Queries

#### 2.3.1 공연 목록 조회

```typescript
// hooks/useEvents.ts
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '@/lib/react-query/queryKeys'
import { getEvents } from '@/lib/api/events'

export function useEvents(params: EventListParams) {
  return useQuery({
    queryKey: queryKeys.events.list(params),
    queryFn: () => getEvents(params),
    staleTime: 1000 * 60 * 5, // 5분
  })
}
```

#### 2.3.2 공연 상세 조회

```typescript
// hooks/useEventDetail.ts
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '@/lib/react-query/queryKeys'
import { getEventDetail } from '@/lib/api/events'

export function useEventDetail(eventId: string) {
  return useQuery({
    queryKey: queryKeys.events.detail(eventId),
    queryFn: () => getEventDetail(eventId),
    staleTime: 1000 * 60 * 5, // 5분
    enabled: !!eventId, // eventId가 있을 때만 실행
  })
}
```

#### 2.3.3 대기열 상태 조회 (폴링)

```typescript
// hooks/useQueueStatus.ts
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '@/lib/react-query/queryKeys'
import { getQueueStatus } from '@/lib/api/queue'

export function useQueueStatus(scheduleId: string) {
  return useQuery({
    queryKey: queryKeys.queue.status(scheduleId),
    queryFn: () => getQueueStatus(scheduleId),
    refetchInterval: 5000, // 5초마다 폴링
    staleTime: 0, // 항상 최신 데이터 요청
    enabled: !!scheduleId,
  })
}
```

#### 2.3.4 좌석 상태 조회

```typescript
// hooks/useSeats.ts
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '@/lib/react-query/queryKeys'
import { getSeats } from '@/lib/api/reservations'

export function useSeats(scheduleId: string) {
  return useQuery({
    queryKey: queryKeys.events.seats(scheduleId),
    queryFn: () => getSeats(scheduleId),
    staleTime: 1000 * 30, // 30초 (자주 변경되므로 짧게)
    refetchInterval: 10000, // 10초마다 자동 갱신
  })
}
```

### 2.4 주요 Mutations

#### 2.4.1 좌석 선점

```typescript
// hooks/useHoldSeat.ts
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/lib/react-query/queryKeys'
import { holdSeat } from '@/lib/api/reservations'

export function useHoldSeat() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: holdSeat,
    onSuccess: (data, variables) => {
      // 좌석 상태 캐시 무효화
      queryClient.invalidateQueries({
        queryKey: queryKeys.events.seats(variables.scheduleId),
      })
    },
    onError: (error) => {
      toast.error('좌석 선점에 실패했습니다.')
    },
  })
}
```

#### 2.4.2 결제 요청

```typescript
// hooks/usePayment.ts
import { useMutation } from '@tanstack/react-query'
import { createPayment } from '@/lib/api/payments'

export function usePayment() {
  return useMutation({
    mutationFn: createPayment,
    onSuccess: (data) => {
      // PortOne 결제 위젯 열기
      openPortOneWidget(data.paymentId)
    },
    onError: (error) => {
      toast.error('결제 요청에 실패했습니다.')
    },
  })
}
```

---

## 3. Client State 관리 (Zustand)

### 3.1 인증 스토어

```typescript
// stores/authStore.ts
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AuthState {
  user: User | null
  accessToken: string | null
  isAuthenticated: boolean

  setUser: (user: User) => void
  setAccessToken: (token: string) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      accessToken: null,
      isAuthenticated: false,

      setUser: (user) => set({ user, isAuthenticated: true }),
      setAccessToken: (token) => set({ accessToken }),
      logout: () => set({ user: null, accessToken: null, isAuthenticated: false }),
    }),
    {
      name: 'auth-storage', // localStorage 키
      partialize: (state) => ({ user: state.user }), // user만 persist
    }
  )
)
```

### 3.2 대기열 스토어

```typescript
// stores/queueStore.ts
import { create } from 'zustand'

interface QueueState {
  queueToken: string | null
  scheduleId: string | null
  position: number | null

  setQueueToken: (token: string, scheduleId: string) => void
  clearQueueToken: () => void
}

export const useQueueStore = create<QueueState>((set) => ({
  queueToken: null,
  scheduleId: null,
  position: null,

  setQueueToken: (token, scheduleId) => set({ queueToken, scheduleId }),
  clearQueueToken: () => set({ queueToken: null, scheduleId: null, position: null }),
}))
```

### 3.3 예매 스토어 (좌석 선택 임시 저장)

```typescript
// stores/reservationStore.ts
import { create } from 'zustand'

interface ReservationState {
  selectedSeats: Seat[]
  scheduleId: string | null
  totalPrice: number

  addSeat: (seat: Seat) => void
  removeSeat: (seatId: string) => void
  clearSeats: () => void
  setScheduleId: (scheduleId: string) => void
}

export const useReservationStore = create<ReservationState>((set) => ({
  selectedSeats: [],
  scheduleId: null,
  totalPrice: 0,

  addSeat: (seat) => set((state) => {
    const newSeats = [...state.selectedSeats, seat]
    const totalPrice = newSeats.reduce((sum, s) => sum + s.price, 0)
    return { selectedSeats: newSeats, totalPrice }
  }),

  removeSeat: (seatId) => set((state) => {
    const newSeats = state.selectedSeats.filter((s) => s.id !== seatId)
    const totalPrice = newSeats.reduce((sum, s) => sum + s.price, 0)
    return { selectedSeats: newSeats, totalPrice }
  }),

  clearSeats: () => set({ selectedSeats: [], totalPrice: 0 }),
  setScheduleId: (scheduleId) => set({ scheduleId }),
}))
```

---

## 4. API 호출 패턴

### 4.1 Axios 인스턴스 설정

```typescript
// lib/api/axios.ts
import axios from 'axios'
import { useAuthStore } from '@/stores/authStore'

export const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request Interceptor (Access Token 자동 추가)
apiClient.interceptors.request.use((config) => {
  const { accessToken } = useAuthStore.getState()

  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`
  }

  return config
})

// Response Interceptor (에러 처리)
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config

    // Access Token 만료 시 Refresh Token으로 갱신
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true

      try {
        const newAccessToken = await refreshAccessToken()
        useAuthStore.getState().setAccessToken(newAccessToken)

        originalRequest.headers.Authorization = `Bearer ${newAccessToken}`
        return apiClient(originalRequest)
      } catch (refreshError) {
        // Refresh Token도 만료된 경우 로그아웃
        useAuthStore.getState().logout()
        window.location.href = '/login'
        return Promise.reject(refreshError)
      }
    }

    return Promise.reject(error)
  }
)
```

### 4.2 API 함수 예시

#### 4.2.1 공연 목록 조회

```typescript
// lib/api/events.ts
import { apiClient } from './axios'

export interface EventListParams {
  page?: number
  size?: number
  sort?: string
  keyword?: string
}

export async function getEvents(params: EventListParams) {
  const { data } = await apiClient.get('/events', { params })
  return data
}

export async function getEventDetail(eventId: string) {
  const { data } = await apiClient.get(`/events/${eventId}`)
  return data
}

export async function getSeats(scheduleId: string) {
  const { data } = await apiClient.get(`/events/schedules/${scheduleId}/seats`)
  return data
}
```

#### 4.2.2 대기열 API

```typescript
// lib/api/queue.ts
import { apiClient } from './axios'

export async function enterQueue(scheduleId: string) {
  const { data } = await apiClient.post('/queue/enter', { scheduleId })
  return data
}

export async function getQueueStatus(scheduleId: string) {
  const { data } = await apiClient.get('/queue/status', {
    params: { scheduleId },
  })
  return data
}

export async function leaveQueue(scheduleId: string) {
  await apiClient.delete('/queue/leave', { params: { scheduleId } })
}
```

#### 4.2.3 예매 API

```typescript
// lib/api/reservations.ts
import { apiClient } from './axios'

export async function holdSeat(params: {
  scheduleId: string
  seatIds: string[]
}) {
  const { data } = await apiClient.post('/reservations/hold', params)
  return data
}

export async function getReservations() {
  const { data } = await apiClient.get('/reservations')
  return data
}

export async function cancelReservation(reservationId: string) {
  await apiClient.delete(`/reservations/${reservationId}`)
}
```

#### 4.2.4 결제 API

```typescript
// lib/api/payments.ts
import { apiClient } from './axios'

export async function createPayment(params: {
  reservationId: string
  paymentMethod: string
}) {
  const { data } = await apiClient.post('/payments', params)
  return data
}

export async function confirmPayment(params: {
  paymentId: string
  impUid: string
}) {
  const { data } = await apiClient.post('/payments/confirm', params)
  return data
}
```

---

## 5. 에러/로딩 상태 처리 전략

### 5.1 에러 처리

#### 5.1.1 React Query 에러 바운더리

```typescript
// components/ErrorBoundary.tsx
import { QueryErrorResetBoundary } from '@tanstack/react-query'
import { ErrorBoundary as ReactErrorBoundary } from 'react-error-boundary'

export function ErrorBoundary({ children }: { children: React.ReactNode }) {
  return (
    <QueryErrorResetBoundary>
      {({ reset }) => (
        <ReactErrorBoundary
          onReset={reset}
          fallbackRender={({ error, resetErrorBoundary }) => (
            <div>
              <h2>문제가 발생했습니다</h2>
              <pre>{error.message}</pre>
              <button onClick={resetErrorBoundary}>다시 시도</button>
            </div>
          )}
        >
          {children}
        </ReactErrorBoundary>
      )}
    </QueryErrorResetBoundary>
  )
}
```

#### 5.1.2 에러 타입별 처리

```typescript
// lib/errors/errorHandler.ts
export function handleApiError(error: unknown) {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    const code = error.response?.data?.code

    switch (status) {
      case 400:
        toast.error('잘못된 요청입니다.')
        break
      case 401:
        toast.error('인증이 필요합니다.')
        window.location.href = '/login'
        break
      case 403:
        toast.error('권한이 없습니다.')
        break
      case 404:
        toast.error('요청하신 리소스를 찾을 수 없습니다.')
        break
      case 409:
        if (code === 'SEAT_ALREADY_HELD') {
          toast.error('이미 선점된 좌석입니다.')
        } else if (code === 'MAX_SEATS_EXCEEDED') {
          toast.error('최대 4장까지만 예매할 수 있습니다.')
        }
        break
      case 429:
        toast.error('요청이 너무 많습니다. 잠시 후 다시 시도해주세요.')
        break
      case 503:
        toast.error('서비스를 일시적으로 사용할 수 없습니다.')
        break
      default:
        toast.error('알 수 없는 오류가 발생했습니다.')
    }
  }
}
```

### 5.2 로딩 상태 처리

#### 5.2.1 Suspense 활용

```typescript
// app/(main)/events/page.tsx
import { Suspense } from 'react'
import EventList from '@/components/domain/event/EventList'
import EventListSkeleton from '@/components/domain/event/EventListSkeleton'

export default function EventsPage() {
  return (
    <Suspense fallback={<EventListSkeleton />}>
      <EventList />
    </Suspense>
  )
}
```

#### 5.2.2 React Query isLoading 활용

```typescript
// components/domain/queue/QueueStatus.tsx
'use client'

import { useQueueStatus } from '@/hooks/useQueueStatus'

export default function QueueStatus({ scheduleId }: { scheduleId: string }) {
  const { data, isLoading, error } = useQueueStatus(scheduleId)

  if (isLoading) return <Spinner />
  if (error) return <ErrorMessage error={error} />

  return (
    <div>
      <p>대기 순서: {data.position}번</p>
      <p>예상 대기 시간: {data.estimatedTime}분</p>
    </div>
  )
}
```

---

## 6. 백엔드 API 명세 연동 매핑

| 프론트엔드 Hook | 백엔드 API | 문서 참조 |
|---------------|-----------|----------|
| `useEvents()` | `GET /events` | [02_event_service.md](../specification/02_event_service.md#11-공연-목록-조회) |
| `useEventDetail()` | `GET /events/{id}` | [02_event_service.md](../specification/02_event_service.md#12-공연-상세-조회) |
| `useSeats()` | `GET /events/schedules/{id}/seats` | [02_event_service.md](../specification/02_event_service.md#13-좌석-정보-조회) |
| `useEnterQueue()` | `POST /queue/enter` | [03_queue_service.md](../specification/03_queue_service.md#11-대기열-진입) |
| `useQueueStatus()` | `GET /queue/status` | [03_queue_service.md](../specification/03_queue_service.md#12-대기열-상태-조회) |
| `useHoldSeat()` | `POST /reservations/hold` | [04_reservation_service.md](../specification/04_reservation_service.md#12-좌석-선점) |
| `useReservations()` | `GET /reservations` | [04_reservation_service.md](../specification/04_reservation_service.md#13-예매-내역-조회) |
| `usePayment()` | `POST /payments` | [05_payment_service.md](../specification/05_payment_service.md#11-결제-요청) |
| `useConfirmPayment()` | `POST /payments/confirm` | [05_payment_service.md](../specification/05_payment_service.md#12-결제-승인) |

---

## 7. 참조 문서

- **페이지 구조**: [01_pages.md](./01_pages.md)
- **컴포넌트 설계**: [02_components.md](./02_components.md)
- **백엔드 API 명세**: [`docs/specification/`](../specification/)
