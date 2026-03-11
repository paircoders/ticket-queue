import type { EventDetail, SeatGrade } from '@/types/event'

interface EventStructuredDataProps {
  event: EventDetail
  grades?: SeatGrade[]
}

function getAvailability(status: string): string {
  switch (status) {
    case 'OPEN':
      return 'https://schema.org/InStock'
    case 'PREPARING':
      return 'https://schema.org/PreOrder'
    case 'ENDED':
    case 'CANCELLED':
      return 'https://schema.org/Discontinued'
    default:
      return 'https://schema.org/SoldOut'
  }
}

function getEventStatus(status: string): string {
  switch (status) {
    case 'CANCELLED':
      return 'https://schema.org/EventCancelled'
    case 'ENDED':
      return 'https://schema.org/EventScheduled'
    default:
      return 'https://schema.org/EventScheduled'
  }
}

export function EventStructuredData({ event, grades }: EventStructuredDataProps) {
  const allTimes = event.schedules.flatMap((s) => s.times)
  const startDate = allTimes.length > 0 ? allTimes[0].eventStartAt : undefined
  const endDate = allTimes.length > 0 ? allTimes[allTimes.length - 1].eventEndAt : undefined

  const prices = grades?.map((g) => g.price) ?? []
  const lowPrice = prices.length > 0 ? Math.min(...prices) : undefined
  const highPrice = prices.length > 0 ? Math.max(...prices) : undefined

  const jsonLd: Record<string, unknown> = {
    '@context': 'https://schema.org',
    '@type': 'Event',
    name: event.title,
    ...(event.description && { description: event.description }),
    performer: {
      '@type': 'Person',
      name: event.artist,
    },
    ...(startDate && { startDate }),
    ...(endDate && { endDate }),
    location: {
      '@type': 'Place',
      name: event.venueName,
      address: {
        '@type': 'PostalAddress',
        addressCountry: 'KR',
      },
    },
    eventStatus: getEventStatus(event.status),
    eventAttendanceMode: 'https://schema.org/OfflineEventAttendanceMode',
    ...(prices.length > 0 && {
      offers: {
        '@type': 'AggregateOffer',
        lowPrice,
        highPrice,
        priceCurrency: 'KRW',
        availability: getAvailability(event.status),
      },
    }),
  }

  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
    />
  )
}
