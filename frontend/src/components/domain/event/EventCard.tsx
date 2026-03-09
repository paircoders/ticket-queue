import Image from 'next/image'
import Link from 'next/link'
import { Music } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import type { EventSummary } from '@/types/event'

interface EventCardProps {
  event: EventSummary
  priority?: boolean
}

function formatDateRange(startDate: string, endDate: string): string {
  const start = new Date(startDate)
  const end = new Date(endDate)
  const fmt = (d: Date) =>
    `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
  if (startDate === endDate) return fmt(start)
  return `${fmt(start)} ~ ${fmt(end)}`
}

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'danger' }> = {
    OPEN: { label: '예매중', variant: 'success' },
    PREPARING: { label: '준비중', variant: 'warning' },
    ENDED: { label: '종료', variant: 'secondary' },
    CANCELLED: { label: '취소', variant: 'danger' },
  }
  const { label, variant } = map[status] ?? { label: status, variant: 'outline' }
  return <Badge variant={variant}>{label}</Badge>
}

export function EventCard({ event, priority = false }: EventCardProps) {
  return (
    <Link href={`/events/${event.id}`} className="block group focus:outline-none">
      <Card className="overflow-hidden transition-all duration-200 hover:shadow-lg hover:scale-[1.02] group-focus-visible:ring-2 group-focus-visible:ring-ring group-focus-visible:ring-offset-2">
        {/* 포스터 이미지 영역 */}
        <div className="relative w-full" style={{ aspectRatio: '3/4' }}>
          {event.posterUrl ? (
            <Image
              src={event.posterUrl}
              alt={`${event.title} 포스터`}
              fill
              className="object-cover"
              priority={priority}
              sizes="(max-width: 768px) 100vw, (max-width: 1200px) 50vw, 33vw"
            />
          ) : (
            <div
              className={cn(
                'w-full h-full flex flex-col items-center justify-center',
                'bg-gradient-to-br from-primary-100 to-primary-300'
              )}
            >
              <Music className="w-16 h-16 text-primary-600 mb-2" aria-hidden="true" />
              <span className="text-sm text-primary-700 font-medium">{event.artist}</span>
            </div>
          )}
        </div>

        <CardContent className="p-4 space-y-2">
          <div className="flex items-start justify-between gap-2">
            <h3 className="font-semibold text-foreground line-clamp-2 flex-1">{event.title}</h3>
            <StatusBadge status={event.status} />
          </div>
          <p className="text-sm text-muted-foreground">{event.artist}</p>
          <p className="text-sm text-muted-foreground">{event.venueName}</p>
          <p className="text-caption text-muted-foreground">
            {formatDateRange(event.startDate, event.endDate)}
          </p>
        </CardContent>
      </Card>
    </Link>
  )
}
