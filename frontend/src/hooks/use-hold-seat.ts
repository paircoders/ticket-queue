'use client'

import { useMutation } from '@tanstack/react-query'
import { useRouter } from 'next/navigation'
import { toast } from 'sonner'
import { apiClient } from '@/lib/api/axios'
import { useReservationStore } from '@/stores/reservation-store'
import { ERROR_CODES } from '@/lib/api/error-codes'
import type { HoldRequest, HoldResponse } from '@/types/reservation'

export function useHoldSeat(scheduleId: string) {
  const router = useRouter()
  const { setHoldResult, resetReservation } = useReservationStore()

  return useMutation<HoldResponse, Error, { seatIds: string[] }>({
    mutationFn: async ({ seatIds }) => {
      const response = await apiClient.post<HoldResponse>('/reservations/hold', {
        scheduleId,
        seatIds,
      } satisfies HoldRequest)
      return response.data
    },
    onSuccess: (data) => {
      if (!data.reservationId) {
        toast.error('예매 정보를 받아오지 못했습니다. 다시 시도해 주세요.')
        return
      }
      setHoldResult(data.reservationId, data.holdExpiresAt)
      router.push(`/payment/${data.reservationId}`)
    },
    onError: (error: any) => {
      const code = error?.response?.data?.code
      if (code === ERROR_CODES.SEAT_ALREADY_HELD) {
        toast.error('이미 선점된 좌석이 포함되어 있습니다.')
      } else if (code === ERROR_CODES.MAX_SEATS_EXCEEDED) {
        toast.error('최대 4장까지 선택 가능합니다.')
      } else {
        toast.error('좌석 선점에 실패했습니다. 다시 시도해 주세요.')
      }
      resetReservation()
    },
  })
}
