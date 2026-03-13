'use client'

import Link from 'next/link'
import { useEffect, useMemo } from 'react'
import { toast } from 'sonner'
import { useSeatsWithAutoDeselect } from '@/hooks/use-seats'
import { useReservationStore } from '@/stores/reservation-store'
import { SeatButton } from './SeatButton'
import type { ReservationSeat } from '@/types/reservation'

interface SeatMapProps {
  scheduleId: string
  isHoldPending: boolean
  onPollingError: (hasError: boolean) => void
}

function SeatMapSkeleton() {
  return (
    <div className="flex flex-col gap-2" aria-busy="true" aria-label="좌석 정보 불러오는 중">
      {Array.from({ length: 5 }).map((_, rowIdx) => (
        <div key={rowIdx} className="flex items-center gap-2">
          <div className="w-5 h-5 rounded bg-gray-200 animate-pulse shrink-0" />
          <div className="flex gap-1">
            {Array.from({ length: 10 }).map((_, colIdx) => (
              <div
                key={colIdx}
                className="w-8 h-8 rounded bg-gray-200 animate-pulse"
              />
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

function groupSeatsByRow(seats: ReservationSeat[]): Map<number, ReservationSeat[]> {
  const map = new Map<number, ReservationSeat[]>()
  for (const seat of seats) {
    const existing = map.get(seat.row)
    if (existing) {
      existing.push(seat)
    } else {
      map.set(seat.row, [seat])
    }
  }
  // Sort seats within each row by col
  for (const [, rowSeats] of map) {
    rowSeats.sort((a, b) => a.col - b.col)
  }
  return map
}

/**
 * row는 1-based여야 합니다. ReservationSeat.row >= 1 이 보장되어야 합니다.
 * row=0이면 '@'가 생성되므로 런타임에 검증합니다.
 */
function rowToAlpha(row: number): string {
  if (row < 1) {
    throw new Error(`rowToAlpha: row는 1 이상이어야 합니다. 받은 값: ${row}`)
  }
  return String.fromCharCode(64 + row)
}

export function SeatMap({ scheduleId, isHoldPending, onPollingError }: SeatMapProps) {
  const { data, isLoading, isError, isRefetchError, refetch, failureCount } = useSeatsWithAutoDeselect(scheduleId)
  const selectedSeats = useReservationStore((s) => s.selectedSeats)
  const addSeat = useReservationStore((s) => s.addSeat)
  const removeSeat = useReservationStore((s) => s.removeSeat)

  const seatsByRow = useMemo(() => groupSeatsByRow(data?.seats ?? []), [data?.seats])
  const sortedRows = useMemo(() => Array.from(seatsByRow.keys()).sort((a, b) => a - b), [seatsByRow])
  // selectedIds를 handleSeatClick 전에 정의하여 O(1) 조회에 사용
  const selectedIds = useMemo(() => new Set(selectedSeats.map((s) => s.id)), [selectedSeats])

  // Notify parent when polling fails repeatedly
  useEffect(() => {
    if ((isError || isRefetchError) && failureCount >= 2) {
      onPollingError(true)
    } else if (!isError && !isRefetchError) {
      onPollingError(false)
    }
  }, [isError, isRefetchError, failureCount, onPollingError])

  const handleSeatClick = (seatId: string) => {
    const seat = data?.seats.find((s) => s.seatId === seatId)
    if (!seat) return

    if (selectedIds.has(seatId)) {
      removeSeat(seatId)
    } else {
      if (selectedSeats.length >= 4) {
        toast.warning('최대 4장까지 선택 가능합니다.')
        return
      }
      addSeat({
        id: seat.seatId,
        seatNumber: `${rowToAlpha(seat.row)}${seat.col}`,
        status: seat.status,
        row: seat.row,
        col: seat.col,
        grade: seat.grade,
        price: seat.price,
      })
    }
  }

  if (isLoading) {
    return (
      <div className="w-full overflow-x-auto p-4">
        <SeatMapSkeleton />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-12 text-center">
        <p className="text-gray-600">좌석 정보를 불러오지 못했습니다.</p>
        <button
          type="button"
          onClick={() => refetch()}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          다시 시도
        </button>
      </div>
    )
  }

  const allSeatsTaken = data?.seats && data.seats.every((s) => s.status !== 'AVAILABLE')

  if (allSeatsTaken) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-12 text-center">
        <p className="text-gray-600">현재 선택 가능한 좌석이 없습니다.</p>
        <Link
          href="/events"
          className="rounded-md bg-gray-700 px-4 py-2 text-sm font-medium text-white hover:bg-gray-800 transition-colors"
        >
          공연 목록으로
        </Link>
      </div>
    )
  }

  return (
    <div
      role="group"
      aria-label="좌석 선택 영역"
      className="w-full overflow-x-auto"
    >
      {/* Stage */}
      <div className="mb-6 flex justify-center" aria-hidden="true">
        <div className="w-full max-w-sm rounded-md bg-gray-300 py-2 text-center text-sm font-semibold tracking-widest text-gray-600">
          STAGE
        </div>
      </div>

      {/* Seat grid */}
      <div className="inline-flex flex-col gap-2 min-w-max mx-auto">
        {sortedRows.map((row) => {
          const rowSeats = seatsByRow.get(row) ?? []
          const rowLabel = rowToAlpha(row)

          return (
            <div key={row} className="flex items-center gap-2">
              {/* Row label */}
              <span className="w-5 text-center text-xs font-medium text-gray-500 shrink-0">
                {rowLabel}
              </span>
              {/* Seats */}
              <div className="flex gap-1">
                {rowSeats.map((seat) => (
                  <SeatButton
                    key={seat.seatId}
                    seatId={seat.seatId}
                    row={seat.row}
                    col={seat.col}
                    grade={seat.grade}
                    price={seat.price}
                    status={seat.status}
                    isSelected={selectedIds.has(seat.seatId)}
                    isHoldPending={isHoldPending}
                    onClick={handleSeatClick}
                  />
                ))}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
