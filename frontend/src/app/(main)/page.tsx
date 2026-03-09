import type { Metadata } from 'next'
import Link from 'next/link'
import { getEventsServer } from '@/lib/api/server/events'
import { HeroBanner } from '@/components/domain/event/HeroBanner'
import { EventList } from '@/components/domain/event/EventList'
import type { EventSummary } from '@/types/event'

export const metadata: Metadata = {
  title: '공정한 티켓팅 플랫폼',
  description:
    '대기열 시스템으로 공정한 티켓 예매를 경험하세요. 최신 콘서트, 공연 정보를 확인하고 예매하세요.',
  keywords: ['티켓팅', '공연', '콘서트', '예매', '대기열', 'K-pop'],
  openGraph: {
    title: '공정한 티켓팅 플랫폼',
    description: '대기열 시스템으로 공정한 티켓 예매를 경험하세요.',
    type: 'website',
    locale: 'ko_KR',
    siteName: 'Ticket Queue',
  },
}

export default async function HomePage() {
  let events: EventSummary[] = []
  try {
    const data = await getEventsServer({ size: 6, status: 'OPEN' })
    events = data.list
  } catch (error) {
    // 백엔드 미실행 등 fetch 실패 시 빈 목록으로 fallback
    if (process.env.NODE_ENV !== 'production') {
      console.error('Failed to fetch events', error)
    }
    events = []
  }

  return (
    <>
      <HeroBanner />
      <section className="max-w-7xl mx-auto px-6 py-12">
        <div className="flex items-center justify-between mb-8">
          <h2 className="text-h2 text-foreground">인기 공연</h2>
          <Link href="/events" className="text-primary-600 hover:underline text-sm font-medium">
            전체 보기 →
          </Link>
        </div>
        <EventList events={events} />
      </section>
    </>
  )
}
