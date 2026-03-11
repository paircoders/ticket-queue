import { Music } from 'lucide-react'
import { cn } from '@/lib/utils'
import { StatusBadge } from './StatusBadge'
import { ScheduleList } from './ScheduleList'
import type { EventDetail as EventDetailType } from '@/types/event'

interface EventDetailProps {
  event: EventDetailType
}

export function EventDetail({ event }: EventDetailProps) {
  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
      {/* 포스터 플레이스홀더 */}
      <div className="md:col-span-1">
        <div
          className={cn(
            'w-full flex flex-col items-center justify-center rounded-lg',
            'bg-gradient-to-br from-primary-100 to-primary-300'
          )}
          style={{ aspectRatio: '3/4' }}
        >
          <Music className="w-24 h-24 text-primary-600 mb-3" aria-hidden="true" />
          <span className="text-base text-primary-700 font-medium px-4 text-center">
            {event.artist}
          </span>
        </div>
      </div>

      {/* 공연 정보 */}
      <div className="md:col-span-2 space-y-6">
        <div className="space-y-3">
          <StatusBadge status={event.status} />
          <h1 className="text-3xl font-bold text-foreground">{event.title}</h1>
          <p className="text-lg text-muted-foreground">{event.artist}</p>
          <p className="text-sm text-muted-foreground">
            {event.venueName} · {event.hallName}
          </p>
        </div>

        {event.description && (
          <p className="text-foreground leading-relaxed">{event.description}</p>
        )}

        <ScheduleList schedules={event.schedules} />
      </div>
    </div>
  )
}
