import type { Metadata } from 'next'
import { Suspense } from 'react'
import { getEventsServer } from '@/lib/api/server/events'
import { getMockEventsPage, MOCK_EVENTS } from '@/lib/api/mock/events'
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

  let page = Math.max(1, parseInt(pageParam ?? '1', 10) || 1)

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
      page = totalPages
    }
  } catch (error) {
    if (process.env.NODE_ENV !== 'production') {
      console.error('Failed to fetch events — using mock data', error)
      const maxMockPage = Math.ceil(MOCK_EVENTS.length / PAGE_SIZE) - 1
      const mockData = getMockEventsPage(Math.min(page - 1, maxMockPage), PAGE_SIZE, { keyword, status })
      events = mockData.list
      totalPages = Math.ceil(mockData.totalElements / PAGE_SIZE)
    } else {
      throw error
    }
  }

  return (
    <main style={{ backgroundColor: 'var(--apple-canvas-parchment)', minHeight: '100vh' }}>
      <section
        style={{
          backgroundColor: 'var(--apple-canvas)',
          padding: '80px 48px 64px',
          textAlign: 'center',
        }}
      >
        <h1
          style={{
            fontFamily: 'var(--font-display)',
            fontSize: 'clamp(34px, 5vw, 56px)',
            fontWeight: 600,
            lineHeight: 1.07,
            letterSpacing: '-0.28px',
            color: 'var(--apple-ink)',
            marginBottom: '12px',
          }}
        >
          공연 목록
        </h1>
        <p
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '17px',
            fontWeight: 400,
            lineHeight: 1.47,
            letterSpacing: '-0.374px',
            color: 'var(--apple-ink-muted-48)',
          }}
        >
          진행 중인 공연을 검색하고 예매하세요.
        </p>
      </section>

      <section
        style={{
          maxWidth: '1440px',
          margin: '0 auto',
          padding: '48px 48px 80px',
        }}
      >
        <Suspense fallback={<div style={{ height: '44px', marginBottom: '40px' }} />}>
          <EventFilter defaultKeyword={keyword} defaultStatus={status} />
        </Suspense>
        <EventList events={events} />
        <Pagination
          currentPage={page}
          totalPages={totalPages}
          keyword={keyword}
          status={status}
        />
      </section>
    </main>
  )
}
