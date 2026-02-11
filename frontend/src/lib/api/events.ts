import { apiClient } from './axios'
import type { PaginatedResponse } from '@/types/api'
import type {
  EventSummary,
  EventDetail,
  EventListParams,
  SeatsResponse,
} from '@/types/event'

export async function getEvents(
  params?: EventListParams
): Promise<PaginatedResponse<EventSummary>> {
  const response = await apiClient.get<PaginatedResponse<EventSummary>>(
    '/events',
    { params }
  )
  return response.data
}

export async function getEventDetail(id: string): Promise<EventDetail> {
  const response = await apiClient.get<EventDetail>(`/events/${id}`)
  return response.data
}

export async function getScheduleSeats(
  scheduleId: string
): Promise<SeatsResponse> {
  const response = await apiClient.get<SeatsResponse>(
    `/events/schedules/${scheduleId}/seats`
  )
  return response.data
}
