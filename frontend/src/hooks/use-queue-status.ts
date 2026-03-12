'use client'

import { useQuery } from '@tanstack/react-query'
import { getQueueStatus } from '@/lib/api/queue'
import { queryKeys } from '@/lib/react-query/query-keys'

export function useQueueStatus(scheduleId: string) {
  return useQuery({
    queryKey: queryKeys.queue.status(scheduleId),
    queryFn: () => getQueueStatus(scheduleId),
    refetchInterval: (query) =>
      query.state.data?.status === 'ACTIVE' ? false : 5000,
    staleTime: 0,
    retry: 3,
    enabled: !!scheduleId,
  })
}
