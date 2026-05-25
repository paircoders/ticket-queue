'use client'

import { useQuery } from '@tanstack/react-query'
import { getReservations } from '@/lib/api/reservations'
import { queryKeys } from '@/lib/react-query/query-keys'

export function useReservations() {
  return useQuery({
    queryKey: queryKeys.reservations.list(),
    queryFn: getReservations,
    staleTime: 30_000,
  })
}
