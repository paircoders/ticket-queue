import { EventCard } from './EventCard'
import type { EventSummary } from '@/types/event'

interface EventListProps {
  events: EventSummary[]
}

export function EventList({ events }: EventListProps) {
  if (events.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
        <p className="text-body">현재 진행 중인 공연이 없습니다.</p>
      </div>
    )
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      {events.map((event, index) => (
        <EventCard key={event.id} event={event} priority={index < 3} />
      ))}
    </div>
  )
}
