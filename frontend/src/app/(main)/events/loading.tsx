import { EventListSkeleton } from '@/components/skeleton/event-list-skeleton'

export default function EventsLoading() {
  return (
    <div className="max-w-7xl mx-auto px-6 py-12">
      <EventListSkeleton />
    </div>
  )
}
