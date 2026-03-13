'use client'

import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { toast } from 'sonner'
import { queryKeys } from '@/lib/react-query/query-keys'
import { apiClient } from '@/lib/api/axios'
import { useReservationStore } from '@/stores/reservation-store'
import type { RealtimeSeatsResponse } from '@/types/reservation'

export function useSeats(scheduleId: string) {
  return useQuery<RealtimeSeatsResponse>({
    queryKey: queryKeys.reservations.seats(scheduleId),
    queryFn: async () => {
      const response = await apiClient.get<RealtimeSeatsResponse>(
        `/reservations/seats/${scheduleId}`
      )
      return response.data
    },
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
    staleTime: 0,
    placeholderData: (prev) => prev,
    enabled: !!scheduleId,
  })
}

export function useSeatsWithAutoDeselect(scheduleId: string) {
  const query = useSeats(scheduleId)
  const { selectedSeats, removeSeat } = useReservationStore()

  useEffect(() => {
    if (!query.data?.seats) return

    const unavailableIds = new Set(
      query.data.seats
        .filter((seat) => seat.status === 'HOLD' || seat.status === 'SOLD')
        .map((seat) => seat.seatId)
    )

    const deselected = selectedSeats.filter((selected) =>
      unavailableIds.has(selected.id)
    )

    if (deselected.length === 0) return

    deselected.forEach((seat) => removeSeat(seat.id))

    toast.warning(
      deselected.length === 1
        ? '선택하신 좌석이 다른 사람에게 선점되어 자동 해제되었습니다.'
        : `선택하신 좌석 ${deselected.length}개가 다른 사람에게 선점되어 자동 해제되었습니다.`
    )
  }, [query.data, selectedSeats, removeSeat])

  return query
}
