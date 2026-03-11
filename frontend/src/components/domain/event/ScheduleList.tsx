import Link from 'next/link'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { ScheduleDate, ScheduleTime } from '@/types/event'

const DAYS_KO = ['일', '월', '화', '수', '목', '금', '토']

function formatDate(dateStr: string): string {
  const [year, month, day] = dateStr.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  const dow = DAYS_KO[date.getDay()]
  return `${year}.${String(month).padStart(2, '0')}.${String(day).padStart(2, '0')} (${dow})`
}

function formatTime(datetimeStr: string): string {
  const timePart = datetimeStr.split('T')[1]
  if (!timePart) return '--:--'
  const [hours, minutes] = timePart.split(':')
  return `${hours}:${minutes}`
}

type TimeStatus = 'cancelled' | 'ended' | 'upcoming' | 'soldout' | 'available'

function getTimeStatus(time: ScheduleTime): TimeStatus {
  if (time.status === 'CANCELLED') return 'cancelled'
  if (time.status === 'ENDED') return 'ended'
  if (new Date(time.saleStartAt) > new Date()) return 'upcoming'
  if (time.isSoldOut) return 'soldout'
  return 'available'
}

interface ScheduleListProps {
  schedules: ScheduleDate[]
}

export function ScheduleList({ schedules }: ScheduleListProps) {
  if (schedules.length === 0) return null

  return (
    <section className="space-y-4">
      <h2 className="text-xl font-semibold text-foreground">공연 일정</h2>
      {schedules.map((schedule) => (
        <div key={schedule.date}>
          <h3 className="text-sm font-medium text-muted-foreground mb-2">
            {formatDate(schedule.date)}
          </h3>
          <div className="space-y-3">
            {schedule.times.map((time) => {
              const status = getTimeStatus(time)
              return (
                <div
                  key={time.id}
                  className="rounded-lg border bg-card p-4 flex items-center justify-between"
                >
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-foreground">{time.playSequence}회</span>
                      {status === 'cancelled' && <Badge variant="danger">취소됨</Badge>}
                      {status === 'ended' && <Badge variant="secondary">종료</Badge>}
                      {status === 'soldout' && <Badge variant="danger">매진</Badge>}
                      {status === 'upcoming' && <Badge variant="secondary">예매 예정</Badge>}
                    </div>
                    <p className="text-sm text-muted-foreground">
                      {formatTime(time.eventStartAt)} ~ {formatTime(time.eventEndAt)}
                    </p>
                  </div>
                  {status === 'available' ? (
                    <Button asChild variant="primary">
                      <Link
                        href={`/queue/${time.id}`}
                        aria-label={`${time.playSequence}회차 예매하기`}
                      >
                        예매하기
                      </Link>
                    </Button>
                  ) : (
                    <Button variant="outline" disabled>
                      {status === 'upcoming' ? '예매 예정' : status === 'cancelled' ? '취소됨' : status === 'ended' ? '종료' : '매진'}
                    </Button>
                  )}
                </div>
              )
            })}
          </div>
        </div>
      ))}
    </section>
  )
}
