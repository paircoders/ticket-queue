import type { Metadata } from 'next'
import { getEventsServer } from '@/lib/api/server/events'
import { EventList } from '@/components/domain/event/EventList'
import { EventFilter } from '@/components/domain/event/EventFilter'
import { Pagination } from '@/components/ui/Pagination'
import type { EventSummary } from '@/types/event'

const PAGE_SIZE = 12

export const metadata: Metadata = {
  title: '공연 목록 | Ticket Queue',
  description: '진행 중인 공연 목록을 확인하고 예매를 시작하세요. 키워드 검색과 상태 필터로 원하는 공연을 찾아보세요.',
  openGraph: {
    title: '공연 목록 | Ticket Queue',
    description: '진행 중인 공연 목록을 확인하고 예매를 시작하세요.',
    type: 'website',
    locale: 'ko_KR',
    siteName: 'Ticket Queue',
  },
}

interface EventsPageProps {
  searchParams: Promise<{ page?: string; keyword?: string; status?: string }>
}

export default async function EventsPage({ searchParams }: EventsPageProps) {
  const { page: pageParam, keyword, status } = await searchParams

  const page = Math.max(1, parseInt(pageParam ?? '1', 10) || 1)

  let events: EventSummary[] = []
  let totalPages = 0

  try {
    const data = await getEventsServer({
      page: page - 1,
      size: PAGE_SIZE,
      keyword: keyword || undefined,
      status: status || undefined,
    })
    events = data.list
    totalPages = Math.ceil(data.totalElements / PAGE_SIZE)

    if (page > totalPages && totalPages > 0) {
      const corrected = await getEventsServer({
        page: totalPages - 1,
        size: PAGE_SIZE,
        keyword: keyword || undefined,
        status: status || undefined,
      })
      events = corrected.list
      totalPages = Math.ceil(corrected.totalElements / PAGE_SIZE)
    }
  } catch (error) {
    if (process.env.NODE_ENV !== 'production') {
      console.error('Failed to fetch events', error)
    }
    events = []
  }

  function createPageUrl(p: number) {
    const params = new URLSearchParams()
    if (keyword) params.set('keyword', keyword)
    if (status) params.set('status', status)
    params.set('page', String(p))
    return '/events?' + params.toString()
  }

  return (
    <main className="max-w-7xl mx-auto px-6 py-12">
      <div className="mb-8">
        <h1 className="text-h1 text-foreground mb-2">공연 목록</h1>
        <p className="text-muted-foreground">진행 중인 공연을 검색하고 예매하세요.</p>
      </div>

      <EventFilter defaultKeyword={keyword} defaultStatus={status} />

      <EventList events={events} />

      <Pagination
        currentPage={page}
        totalPages={totalPages}
        createPageUrl={createPageUrl}
      />
    </main>
  )
}
