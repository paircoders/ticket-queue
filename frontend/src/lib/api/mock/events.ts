import type { PaginatedResponse } from '@/types/api'
import type { EventSummary } from '@/types/event'

export const MOCK_EVENTS: EventSummary[] = [
  {
    id: 'mock-1',
    title: 'BTS World Tour: Permission to Dance',
    artist: 'BTS',
    venueName: '올림픽주경기장',
    posterUrl: undefined,
    startDate: '2026-06-15',
    endDate: '2026-06-17',
    status: 'OPEN',
  },
  {
    id: 'mock-2',
    title: 'BLACKPINK BORN PINK World Tour',
    artist: 'BLACKPINK',
    venueName: '고척스카이돔',
    posterUrl: undefined,
    startDate: '2026-07-05',
    endDate: '2026-07-06',
    status: 'OPEN',
  },
  {
    id: 'mock-3',
    title: 'IU Concert: The Golden Hour',
    artist: 'IU (아이유)',
    venueName: '잠실실내체육관',
    posterUrl: undefined,
    startDate: '2026-05-20',
    endDate: '2026-05-21',
    status: 'PREPARING',
  },
  {
    id: 'mock-4',
    title: 'aespa MY WORLD Tour',
    artist: 'aespa',
    venueName: 'KSPO DOME',
    posterUrl: undefined,
    startDate: '2026-08-10',
    endDate: '2026-08-11',
    status: 'OPEN',
  },
  {
    id: 'mock-5',
    title: 'NewJeans OMG Concert',
    artist: 'NewJeans',
    venueName: '올림픽공원 체조경기장',
    posterUrl: undefined,
    startDate: '2026-09-01',
    endDate: '2026-09-02',
    status: 'PREPARING',
  },
  {
    id: 'mock-6',
    title: 'SEVENTEEN Power of Love',
    artist: 'SEVENTEEN',
    venueName: '인천 아시아드주경기장',
    posterUrl: undefined,
    startDate: '2026-04-10',
    endDate: '2026-04-11',
    status: 'ENDED',
  },
  {
    id: 'mock-7',
    title: 'EXO Planet #6 — The EXploration',
    artist: 'EXO',
    venueName: '수원월드컵경기장',
    posterUrl: undefined,
    startDate: '2026-10-15',
    endDate: '2026-10-16',
    status: 'OPEN',
  },
  {
    id: 'mock-8',
    title: 'TWICE World Tour Ready to Be',
    artist: 'TWICE',
    venueName: '고척스카이돔',
    posterUrl: undefined,
    startDate: '2026-11-05',
    endDate: '2026-11-06',
    status: 'PREPARING',
  },
  {
    id: 'mock-9',
    title: 'STRAY KIDS MANIAC World Tour',
    artist: 'Stray Kids',
    venueName: '올림픽주경기장',
    posterUrl: undefined,
    startDate: '2026-12-20',
    endDate: '2026-12-21',
    status: 'OPEN',
  },
]

export function getMockEventsPage(
  page: number,
  size: number,
  options?: { keyword?: string; status?: string },
): PaginatedResponse<EventSummary> {
  let filtered = MOCK_EVENTS
  if (options?.keyword) {
    const kw = options.keyword.toLowerCase()
    filtered = filtered.filter(
      (e) =>
        e.title?.toLowerCase().includes(kw) ||
        e.artist?.toLowerCase().includes(kw),
    )
  }
  if (options?.status) {
    filtered = filtered.filter((e) => e.status === options.status)
  }
  const start = page * size
  const list = filtered.slice(start, start + size)
  return {
    list,
    totalElements: filtered.length,
    page,
    size,
  }
}
