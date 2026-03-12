'use client'

import { useMutation } from '@tanstack/react-query'
import { enterQueue } from '@/lib/api/queue'
import type { QueueEnterRequest, QueueEnterResponse } from '@/types/queue'

export function useEnterQueue() {
  return useMutation<QueueEnterResponse, Error, QueueEnterRequest>({
    mutationFn: enterQueue,
  })
}
