'use client'

import { cn } from '@/lib/utils'

interface SeatButtonProps {
  seatId: string
  row: number
  col: number
  grade: string
  price: number
  status: 'AVAILABLE' | 'HOLD' | 'SOLD'
  isSelected: boolean
  isHoldPending: boolean
  onClick: (seatId: string) => void
}

function rowToAlpha(row: number): string {
  return String.fromCharCode(64 + row)
}

function formatPrice(price: number): string {
  return price.toLocaleString('ko-KR')
}

function getStatusLabel(status: 'AVAILABLE' | 'HOLD' | 'SOLD', isSelected: boolean): string {
  if (status === 'HOLD') return '선점됨'
  if (status === 'SOLD') return '판매완료'
  return isSelected ? '선택됨' : '선택 가능'
}

export function SeatButton({
  seatId,
  row,
  col,
  grade,
  price,
  status,
  isSelected,
  isHoldPending,
  onClick,
}: SeatButtonProps) {
  const isDisabled = status === 'HOLD' || status === 'SOLD'
  const rowLabel = rowToAlpha(row)
  const label = `${rowLabel}${col}`
  const ariaLabel = `${rowLabel}열 ${col}번 좌석, ${formatPrice(price)}원, ${getStatusLabel(status, isSelected)}`

  function handleClick() {
    if (isDisabled || isHoldPending) return
    onClick(seatId)
  }

  return (
    <button
      type="button"
      disabled={isDisabled || isHoldPending}
      aria-pressed={isSelected}
      aria-busy={isHoldPending ? 'true' : undefined}
      aria-label={ariaLabel}
      onClick={handleClick}
      className={cn(
        'w-8 h-8 rounded text-xs font-medium text-white transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1',
        {
          'bg-green-500 hover:bg-green-600 focus-visible:ring-green-400':
            status === 'AVAILABLE' && !isSelected,
          'bg-blue-500 ring-2 ring-blue-300 focus-visible:ring-blue-400':
            status === 'AVAILABLE' && isSelected,
          'bg-gray-400 cursor-not-allowed': status === 'HOLD',
          'bg-red-500 cursor-not-allowed': status === 'SOLD',
          'pointer-events-none': isHoldPending,
        },
      )}
    >
      {label}
    </button>
  )
}
