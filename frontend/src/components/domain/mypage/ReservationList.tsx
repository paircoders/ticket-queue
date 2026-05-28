'use client'

import { Skeleton } from '@/components/ui/skeleton'
import { useReservations } from '@/hooks/use-reservations'
import { ReservationCard } from './ReservationCard'

interface ReservationListProps {
  /** 표시할 최대 건수. 미지정 시 전체 표시 */
  limit?: number
  emptyMessage?: string
}

export function ReservationList({
  limit,
  emptyMessage = '예매 내역이 없습니다.',
}: ReservationListProps) {
  const { data, isLoading, isError } = useReservations()

  if (isLoading) {
    return (
      <div className="grid gap-4 sm:grid-cols-2">
        {Array.from({ length: limit ?? 4 }).map((_, i) => (
          <Skeleton key={i} className="h-44 w-full rounded-xl" />
        ))}
      </div>
    )
  }

  if (isError) {
    return (
      <div
        role="alert"
        className="rounded-lg border border-destructive/30 bg-destructive/5 p-6 text-destructive"
      >
        예매 내역을 불러오지 못했습니다.
      </div>
    )
  }

  const list = data?.list ?? []
  const visible = typeof limit === 'number' ? list.slice(0, limit) : list

  if (visible.length === 0) {
    return (
      <div className="rounded-lg border border-dashed bg-muted/30 p-8 text-center text-muted-foreground">
        {emptyMessage}
      </div>
    )
  }

  return (
    <ul className="grid gap-4 sm:grid-cols-2">
      {visible.map((reservation) => (
        <li key={reservation.reservationId}>
          <ReservationCard reservation={reservation} />
        </li>
      ))}
    </ul>
  )
}
