import Image from 'next/image'
import Link from 'next/link'
import { Music } from 'lucide-react'
import type { CSSProperties } from 'react'
import type { EventSummary } from '@/types/event'

interface EventCardProps {
  event: EventSummary
  priority?: boolean
}

const STATUS_LABEL: Record<string, string> = {
  OPEN: '예매중',
  PREPARING: '준비중',
  ENDED: '종료',
  CANCELLED: '취소',
}

const secondaryTextStyle: CSSProperties = {
  fontFamily: 'var(--font-sans)',
  fontSize: '14px',
  fontWeight: 400,
  lineHeight: 1.43,
  letterSpacing: '-0.224px',
  color: 'var(--apple-ink-muted-48)',
}

function formatDateRange(startDate: string, endDate: string): string {
  const parseLocal = (s: string) => {
    const [y, m, d] = s.split('-').map(Number)
    return { y, m, d }
  }
  const fmt = ({ y, m, d }: { y: number; m: number; d: number }) =>
    `${y}.${String(m).padStart(2, '0')}.${String(d).padStart(2, '0')}`
  const start = parseLocal(startDate)
  const end = parseLocal(endDate)
  if (!start.y || !end.y) return ''
  if (startDate === endDate) return fmt(start)
  return `${fmt(start)} – ${fmt(end)}`
}

export function EventCard({ event, priority = false }: EventCardProps) {
  const label = STATUS_LABEL[event.status] ?? event.status
  const isOpen = event.status === 'OPEN'

  return (
    <Link
      href={`/events/${event.id}`}
      className="block group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-[var(--apple-primary-focus)] focus-visible:rounded-[18px]"
    >
      <article
        style={{
          backgroundColor: 'var(--apple-canvas)',
          border: '1px solid var(--apple-hairline)',
          borderRadius: '18px',
          overflow: 'hidden',
          transition: 'border-color 0.2s ease, transform 0.2s ease',
        }}
        className="group-hover:[border-color:var(--apple-primary)] group-hover:scale-[0.99]"
      >
        <div className="relative w-full" style={{ aspectRatio: '3/4' }}>
          {event.posterUrl ? (
            <Image
              src={event.posterUrl}
              alt={`${event.title} 포스터`}
              fill
              className="object-cover"
              priority={priority}
              sizes="(max-width: 640px) 100vw, (max-width: 1068px) 50vw, 33vw"
              style={{ boxShadow: 'rgba(0,0,0,0.22) 3px 5px 30px 0' }}
            />
          ) : (
            <div
              className="w-full h-full flex flex-col items-center justify-center gap-3"
              style={{ backgroundColor: 'var(--apple-canvas-parchment)' }}
            >
              <Music
                style={{ width: 48, height: 48, color: 'var(--apple-ink-muted-48)' }}
                aria-hidden="true"
              />
              <span style={{ ...secondaryTextStyle, lineHeight: 1 }}>{event.artist}</span>
            </div>
          )}
        </div>

        <div style={{ padding: '20px 24px 24px' }}>
          <span
            style={{
              display: 'inline-block',
              marginBottom: '10px',
              padding: '4px 12px',
              borderRadius: '9999px',
              fontSize: '12px',
              fontWeight: 400,
              fontFamily: 'var(--font-sans)',
              letterSpacing: '-0.12px',
              backgroundColor: isOpen ? 'var(--apple-primary)' : 'var(--apple-divider-soft)',
              color: isOpen ? '#ffffff' : 'var(--apple-ink-muted-48)',
            }}
          >
            {label}
          </span>

          <h3
            style={{
              fontFamily: 'var(--font-display)',
              fontSize: '17px',
              fontWeight: 600,
              lineHeight: 1.24,
              letterSpacing: '-0.374px',
              color: 'var(--apple-ink)',
              marginBottom: '6px',
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            }}
          >
            {event.title}
          </h3>

          <p style={{ ...secondaryTextStyle, marginBottom: '4px' }}>{event.artist}</p>
          <p style={{ ...secondaryTextStyle, marginBottom: '8px' }}>{event.venueName}</p>

          <p
            style={{
              fontFamily: 'var(--font-sans)',
              fontSize: '12px',
              fontWeight: 400,
              lineHeight: 1.0,
              letterSpacing: '-0.12px',
              color: 'var(--apple-ink-muted-48)',
            }}
          >
            {formatDateRange(event.startDate, event.endDate)}
          </p>
        </div>
      </article>
    </Link>
  )
}
