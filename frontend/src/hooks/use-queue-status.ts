'use client'

import { useQuery } from '@tanstack/react-query'
import axios from 'axios'
import { getQueueStatus } from '@/lib/api/queue'
import { queryKeys } from '@/lib/react-query/query-keys'

export function useQueueStatus(scheduleId: string) {
  return useQuery({
    queryKey: queryKeys.queue.status(scheduleId),
    queryFn: () => getQueueStatus(scheduleId),
    refetchInterval: (query) =>
      query.state.data?.status === 'ACTIVE' ? false : 5000,
    staleTime: 0,
    retry: (failureCount, error) => {
      if (axios.isAxiosError(error)) {
        const status = error.response?.status
        if (status !== undefined && status >= 400 && status < 500) return false
      }
      return failureCount < 3
    },
    enabled: !!scheduleId,
  })
}
