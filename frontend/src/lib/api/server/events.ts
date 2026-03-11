import { cache } from 'react'
import type { PaginatedResponse } from '@/types/api'
import type { EventSummary, EventDetail, EventListParams, SeatsResponse } from '@/types/event'

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL
if (!API_BASE_URL) {
  throw new Error('NEXT_PUBLIC_API_BASE_URL is not defined')
}

export async function getEventsServer(
  params?: EventListParams
): Promise<PaginatedResponse<EventSummary>> {
  const searchParams = new URLSearchParams()
  if (params?.page != null) searchParams.set('page', String(params.page))
  if (params?.size) searchParams.set('size', String(params.size))
  if (params?.status) searchParams.set('status', params.status)
  if (params?.city) searchParams.set('city', params.city)
  if (params?.keyword) searchParams.set('keyword', params.keyword)

  const url = `${API_BASE_URL}/events?${searchParams.toString()}`
  const res = await fetch(url, { next: { revalidate: 300 } })

  if (!res.ok) {
    throw new Error(`Failed to fetch events: ${res.status}`)
  }

  return res.json()
}

export const getEventDetailServer = cache(async (id: string): Promise<EventDetail | null> => {
  const url = `${API_BASE_URL}/events/${id}`
  const res = await fetch(url, { next: { revalidate: 300 } })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Failed to fetch event: ${res.status}`)
  return res.json()
})

export async function getScheduleSeatsServer(scheduleId: string): Promise<SeatsResponse> {
  const url = `${API_BASE_URL}/events/schedules/${scheduleId}/seats`
  const res = await fetch(url, { next: { revalidate: 60 } })
  if (!res.ok) throw new Error(`Failed to fetch seats: ${res.status}`)
  return res.json()
}
