import { EventDetailSkeleton } from '@/components/skeleton/event-detail-skeleton'

export default function Loading() {
  return (
    <div className="max-w-7xl mx-auto px-6 py-12">
      <EventDetailSkeleton />
    </div>
  )
}
