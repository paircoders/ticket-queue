import type { Metadata } from 'next'
import { notFound } from 'next/navigation'
import { getEventDetailServer, getScheduleSeatsServer } from '@/lib/api/server/events'
import { EventDetail } from '@/components/domain/event/EventDetail'
import { SeatInfo } from '@/components/domain/event/SeatInfo'
import { EventStructuredData } from '@/components/domain/event/EventStructuredData'
import type { SeatGrade } from '@/types/event'

interface EventDetailPageProps {
  params: Promise<{ id: string }>
}

export async function generateMetadata({ params }: EventDetailPageProps): Promise<Metadata> {
  const { id } = await params
  const event = await getEventDetailServer(id)
  if (!event) return {}

  const description = event.description ?? `${event.artist} - ${event.venueName}`

  return {
    title: event.title,
    description,
    openGraph: {
      title: event.title,
      description,
      type: 'website',
      locale: 'ko_KR',
    },
    twitter: {
      card: 'summary_large_image',
    },
    alternates: {
      canonical: `https://ticket-queue.com/events/${id}`,
    },
  }
}

export default async function EventDetailPage({ params }: EventDetailPageProps) {
  const { id } = await params
  const event = await getEventDetailServer(id)
  if (!event) notFound()

  let grades: SeatGrade[] = []
  const firstTime = event.schedules[0]?.times[0]
  if (firstTime) {
    try {
      const seatsData = await getScheduleSeatsServer(firstTime.id)
      grades = seatsData.grades
    } catch {
      // 좌석 정보 조회 실패 시 빈 배열로 렌더링
    }
  }

  return (
    <>
      <EventStructuredData event={event} grades={grades} />
      <main className="max-w-7xl mx-auto px-6 py-12">
        <EventDetail event={event} />
        {grades.length > 0 && (
          <div className="mt-12">
            <SeatInfo grades={grades} />
          </div>
        )}
      </main>
    </>
  )
}
