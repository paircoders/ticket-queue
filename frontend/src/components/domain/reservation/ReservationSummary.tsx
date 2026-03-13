'use client'

import { useReservationStore } from '@/stores/reservation-store'

interface ReservationSummaryProps {
  isPollingError: boolean
  isHoldPending: boolean
  onHold: (seatIds: string[]) => void
}

export function ReservationSummary({ isPollingError, isHoldPending, onHold }: ReservationSummaryProps) {
  const selectedSeats = useReservationStore((s) => s.selectedSeats)
  const totalPrice = useReservationStore((s) => s.totalPrice)

  const isDisabled =
    selectedSeats.length === 0 ||
    isHoldPending ||
    isPollingError

  const handlePayment = () => {
    if (isDisabled) return
    onHold(selectedSeats.map((s) => s.id))
  }

  return (
    <div className="space-y-4 rounded-lg border bg-card px-4 py-4">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-foreground">선택 좌석</span>
        <span className="text-sm text-muted-foreground">
          {selectedSeats.length}/4
        </span>
      </div>

      {selectedSeats.length > 0 ? (
        <ul className="space-y-2">
          {selectedSeats.map((seat) => (
            <li key={seat.id} className="flex items-center justify-between text-sm">
              <span className="text-foreground">
                {seat.row}행 {seat.col}열
                <span className="ml-2 text-muted-foreground">{seat.grade}</span>
              </span>
              <span className="text-foreground">
                {seat.price.toLocaleString('ko-KR')}원
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">선택된 좌석이 없습니다.</p>
      )}

      <div className="border-t" />

      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-foreground">총 금액</span>
        <span className="text-base font-bold text-foreground">
          {totalPrice.toLocaleString('ko-KR')}원
        </span>
      </div>

      <button
        type="button"
        onClick={handlePayment}
        disabled={isDisabled}
        className={`w-full rounded-md px-4 py-2.5 text-sm font-semibold transition-colors ${
          isDisabled
            ? 'cursor-not-allowed bg-primary opacity-50 text-primary-foreground'
            : 'bg-primary text-primary-foreground hover:bg-primary/90'
        }`}
      >
        {isHoldPending ? (
          <span className="flex items-center justify-center gap-2">
            <svg
              className="h-4 w-4 animate-spin"
              viewBox="0 0 24 24"
              fill="none"
              aria-hidden="true"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
              />
            </svg>
            처리 중...
          </span>
        ) : (
          '결제하기'
        )}
      </button>
    </div>
  )
}
