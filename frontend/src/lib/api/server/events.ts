import type { PaginatedResponse } from '@/types/api'
import type { EventSummary, EventListParams } from '@/types/event'

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
