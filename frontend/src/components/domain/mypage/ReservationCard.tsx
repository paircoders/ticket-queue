'use client'

import Link from 'next/link'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardFooter, CardHeader } from '@/components/ui/card'
import type { ReservationSummary } from '@/types/reservation'

interface ReservationCardProps {
  reservation: ReservationSummary
}

const STATUS_LABEL: Record<
  string,
  { label: string; variant: 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'danger' }
> = {
  CONFIRMED: { label: '예매 확정', variant: 'success' },
  PENDING: { label: '결제 대기', variant: 'warning' },
  CANCELLED: { label: '취소됨', variant: 'danger' },
  EXPIRED: { label: '만료됨', variant: 'secondary' },
}

export function ReservationCard({ reservation }: ReservationCardProps) {
  const statusInfo =
    STATUS_LABEL[reservation.status] ?? { label: reservation.status, variant: 'secondary' as const }

  return (
    <Card aria-label={`${reservation.eventTitle} 예매 카드`}>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="space-y-1">
            <p className="text-base font-semibold">{reservation.eventTitle}</p>
            <p className="text-sm text-muted-foreground">
              {formatDate(reservation.scheduleDate)}
            </p>
          </div>
          <Badge variant={statusInfo.variant}>{statusInfo.label}</Badge>
        </div>
      </CardHeader>
      <CardContent>
        <ul className="space-y-1 text-sm">
          {reservation.seats.map((seat, idx) => (
            <li key={`${reservation.reservationId}-seat-${idx}`} className="flex justify-between">
              <span>
                {seat.seatNumber}{' '}
                <span className="text-muted-foreground">({seat.grade})</span>
              </span>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-sm font-medium">
          결제 금액: {reservation.paymentAmount.toLocaleString()}원
        </p>
      </CardContent>
      <CardFooter>
        <Button variant="outline" size="sm" asChild>
          <Link href={`/mypage/reservations/${reservation.reservationId}`}>
            상세 보기
          </Link>
        </Button>
      </CardFooter>
    </Card>
  )
}

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
