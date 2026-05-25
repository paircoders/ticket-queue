'use client'

import { HoldTimer } from '@/components/domain/reservation/HoldTimer'
import { Skeleton } from '@/components/ui/skeleton'
import { useReservationDetail } from '@/hooks/use-reservation-detail'

interface PaymentSummaryProps {
  reservationId: string
  scheduleId?: string
  holdExpiresAt?: string
}

export function PaymentSummary({
  reservationId,
  scheduleId,
  holdExpiresAt,
}: PaymentSummaryProps) {
  const { data, isLoading, isError } = useReservationDetail(reservationId)

  if (isLoading) return <PaymentSummarySkeleton />

  if (isError || !data) {
    return (
      <div
        className="rounded-lg border border-destructive/30 bg-destructive/5 p-6 text-center text-destructive"
        role="alert"
      >
        예매 정보를 불러오지 못했습니다.
      </div>
    )
  }

  const resolvedScheduleId = scheduleId ?? data.eventId
  const expiresAt = holdExpiresAt

  return (
    <section
      aria-labelledby="payment-summary-heading"
      className="rounded-lg border bg-card p-6 shadow-sm"
    >
      <h2 id="payment-summary-heading" className="mb-4 text-xl font-bold">
        예매 정보
      </h2>

      <div className="space-y-5">
        <div>
          <h3 className="text-sm font-semibold text-muted-foreground">공연</h3>
          <p className="mt-1 text-base font-medium">{data.eventTitle}</p>
          <p className="text-sm text-muted-foreground">
            {data.artist} · {data.venueName} {data.hallName}
          </p>
          <p className="text-sm text-muted-foreground">
            {formatDate(data.scheduleDate)}
          </p>
        </div>

        <div>
          <h3 className="text-sm font-semibold text-muted-foreground">좌석</h3>
          <ul className="mt-1 space-y-1">
            {data.seats.map((seat) => (
              <li
                key={seat.seatId}
                className="flex justify-between text-sm"
              >
                <span>
                  {seat.seatNumber}{' '}
                  <span className="text-muted-foreground">({seat.grade})</span>
                </span>
                <span>{seat.price.toLocaleString()}원</span>
              </li>
            ))}
          </ul>
        </div>

        <div className="border-t pt-4">
          <div className="flex items-center justify-between text-lg font-bold">
            <span>총 결제 금액</span>
            <span>{data.totalAmount.toLocaleString()}원</span>
          </div>
        </div>

        {expiresAt && (
          <div className="border-t pt-4">
            <HoldTimer
              holdExpiresAt={expiresAt}
              scheduleId={resolvedScheduleId}
            />
          </div>
        )}
      </div>
    </section>
  )
}

function PaymentSummarySkeleton() {
  return (
    <div className="space-y-4 rounded-lg border bg-card p-6 shadow-sm">
      <Skeleton className="h-6 w-32" />
      <Skeleton className="h-4 w-full" />
      <Skeleton className="h-4 w-2/3" />
      <Skeleton className="h-4 w-1/2" />
      <Skeleton className="h-10 w-full" />
    </div>
  )
}

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}
