import { apiClient } from './axios'
import type {
  QueueEnterRequest,
  QueueEnterResponse,
  QueueStatusResponse,
  QueueLeaveResponse,
} from '@/types/queue'

export async function enterQueue(
  data: QueueEnterRequest
): Promise<QueueEnterResponse> {
  const response = await apiClient.post<QueueEnterResponse>(
    '/queue/enter',
    data
  )
  return response.data
}

export async function getQueueStatus(
  scheduleId: string
): Promise<QueueStatusResponse> {
  const response = await apiClient.get<QueueStatusResponse>('/queue/status', {
    params: { scheduleId },
  })
  return response.data
}

export async function leaveQueue(
  scheduleId: string
): Promise<QueueLeaveResponse> {
  const response = await apiClient.delete<QueueLeaveResponse>('/queue/leave', {
    params: { scheduleId },
  })
  return response.data
}
