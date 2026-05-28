'use client'

import { use, useEffect } from 'react'
import { PaymentSummary } from '@/components/domain/payment/PaymentSummary'
import { PaymentWidget } from '@/components/domain/payment/PaymentWidget'
import { useReservationDetail } from '@/hooks/use-reservation-detail'
import { useReservationStore } from '@/stores/reservation-store'

export default function PaymentPage({
  params,
}: {
  params: Promise<{ reservationId: string }>
}) {
  const { reservationId } = use(params)
  const holdExpiresAt = useReservationStore((state) => state.holdExpiresAt)
  const storeScheduleId = useReservationStore((state) => state.scheduleId)
  const setScheduleId = useReservationStore((state) => state.setScheduleId)
  const { data: reservation } = useReservationDetail(reservationId)

  useEffect(() => {
    if (reservation?.eventId && reservation.eventId !== storeScheduleId) {
      setScheduleId(reservation.eventId)
    }
  }, [reservation?.eventId, setScheduleId, storeScheduleId])

  const scheduleId = storeScheduleId ?? reservation?.eventId

  return (
    <main className="mx-auto max-w-2xl px-spacing-md py-spacing-xl">
      <header className="mb-spacing-lg">
        <h1 className="text-3xl font-bold text-gray-900">결제</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          선택한 좌석 정보를 확인하고 결제를 진행해주세요.
        </p>
      </header>

      <div className="space-y-spacing-lg">
        <PaymentSummary
          reservationId={reservationId}
          scheduleId={scheduleId ?? undefined}
          holdExpiresAt={holdExpiresAt ?? undefined}
        />
        <PaymentWidget
          reservationId={reservationId}
          scheduleId={scheduleId ?? undefined}
        />
      </div>
    </main>
  )
}
