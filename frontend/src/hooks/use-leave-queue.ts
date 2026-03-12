'use client'

import { useMutation } from '@tanstack/react-query'
import { leaveQueue } from '@/lib/api/queue'
import type { QueueLeaveResponse } from '@/types/queue'

export function useLeaveQueue() {
  return useMutation<QueueLeaveResponse, Error, string>({
    mutationFn: (scheduleId) => leaveQueue(scheduleId),
  })
}
