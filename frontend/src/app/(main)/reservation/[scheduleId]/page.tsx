'use client'

import { use, useEffect, useState, useCallback } from 'react'
import { useReservationStore } from '@/stores/reservation-store'
import { useHoldSeat } from '@/hooks/use-hold-seat'
import { SeatMap } from '@/components/domain/reservation/SeatMap'
import { SeatLegend } from '@/components/domain/reservation/SeatLegend'
import { ReservationSummary } from '@/components/domain/reservation/ReservationSummary'
import { HoldTimer } from '@/components/domain/reservation/HoldTimer'

export default function ReservationPage({ params }: { params: Promise<{ scheduleId: string }> }) {
  const { scheduleId } = use(params)
  const { setScheduleId, holdExpiresAt } = useReservationStore()
  const [hasPollingError, setHasPollingError] = useState(false)
  const holdMutation = useHoldSeat(scheduleId)

  useEffect(() => {
    setScheduleId(scheduleId)
  }, [scheduleId, setScheduleId])

  useEffect(() => {
    if (!holdExpiresAt) return

    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = '선점 중인 좌석이 있습니다. 5분 내 결제하지 않으면 자동 취소됩니다.'
    }

    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [holdExpiresAt])

  const handlePollingError = useCallback((hasError: boolean) => {
    setHasPollingError(hasError)
  }, [])

  return (
    <div className="flex flex-col">
      {holdExpiresAt && (
        <div className="sticky top-0 z-10 border-b bg-white px-4 py-3 shadow-sm">
          <HoldTimer holdExpiresAt={holdExpiresAt} scheduleId={scheduleId} />
        </div>
      )}

      <main className="flex flex-col gap-6 p-4 md:p-6">
        {hasPollingError && (
          <div
            role="alert"
            className="rounded-md border border-yellow-300 bg-yellow-50 px-4 py-3 text-sm text-yellow-800"
          >
            좌석 정보가 최신이 아닐 수 있습니다. 잠시 후 자동으로 업데이트됩니다.
          </div>
        )}

        <div>
          <h1 className="text-2xl font-bold text-gray-900">좌석 선택</h1>
        </div>

        <SeatLegend />

        <div className="flex flex-col gap-6 md:flex-row md:items-start">
          <div className="flex-1 overflow-x-auto rounded-lg border bg-card p-4">
            <SeatMap
              scheduleId={scheduleId}
              isHoldPending={holdMutation.isPending}
              onPollingError={handlePollingError}
            />
          </div>

          <div className="w-full md:w-80 shrink-0">
            <ReservationSummary
              scheduleId={scheduleId}
              isPollingError={hasPollingError}
              isHoldPending={holdMutation.isPending}
              onHold={(seatIds) => holdMutation.mutate({ seatIds })}
            />
          </div>
        </div>
      </main>
    </div>
  )
}
