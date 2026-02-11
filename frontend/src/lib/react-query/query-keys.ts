import type { EventListParams } from '@/types/event'

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
  payments: {
    all: ['payments'] as const,
    list: () => ['payments', 'list'] as const,
    detail: (id: string) => ['payments', 'detail', id] as const,
  },
  user: {
    profile: () => ['user', 'profile'] as const,
  },
}
