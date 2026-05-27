'use client'

import { useQuery } from '@tanstack/react-query'
import { getReservationDetail } from '@/lib/api/reservations'
import { queryKeys } from '@/lib/react-query/query-keys'

export function useReservationDetail(reservationId: string | undefined) {
  return useQuery({
    queryKey: reservationId
      ? queryKeys.reservations.detail(reservationId)
      : ['reservations', 'detail', 'idle'],
    queryFn: () => getReservationDetail(reservationId as string),
    enabled: Boolean(reservationId),
    staleTime: 30_000,
  })
}
