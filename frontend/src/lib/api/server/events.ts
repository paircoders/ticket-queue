import { cache } from 'react'
import type { PaginatedResponse } from '@/types/api'
import type { EventSummary, EventDetail, EventListParams, SeatsResponse } from '@/types/event'

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL

function ensureApiBaseUrl(): string {
  if (!API_BASE_URL) throw new Error('NEXT_PUBLIC_API_BASE_URL is not defined')
  return API_BASE_URL
}

export const getEventsServer = cache(async (
  params?: EventListParams
): Promise<PaginatedResponse<EventSummary>> => {
  const base = ensureApiBaseUrl()

  const searchParams = new URLSearchParams()
  if (params?.page != null) searchParams.set('page', String(params.page))
  if (params?.size) searchParams.set('size', String(params.size))
  if (params?.status) searchParams.set('status', params.status)
  if (params?.city) searchParams.set('city', params.city)
  if (params?.keyword) searchParams.set('keyword', params.keyword)

  const fetchOptions = params?.keyword
    ? { cache: 'no-store' as const }
    : { next: { revalidate: 300 } }

  const res = await fetch(`${base}/events?${searchParams.toString()}`, fetchOptions)
  if (!res.ok) throw new Error(`Failed to fetch events: ${res.status}`)
  return res.json()
})

export const getEventDetailServer = cache(async (id: string): Promise<EventDetail | null> => {
  const base = ensureApiBaseUrl()
  const res = await fetch(`${base}/events/${id}`, { next: { revalidate: 300 } })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Failed to fetch event: ${res.status}`)
  return res.json()
})

export async function getScheduleSeatsServer(scheduleId: string): Promise<SeatsResponse | null> {
  const base = ensureApiBaseUrl()
  const res = await fetch(`${base}/events/schedules/${scheduleId}/seats`, { next: { revalidate: 60 } })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`Failed to fetch seats: ${res.status}`)
  return res.json()
}
