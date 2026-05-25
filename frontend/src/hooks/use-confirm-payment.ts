'use client'

import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { AxiosError } from 'axios'
import { confirmPayment } from '@/lib/api/payments'
import { queryKeys } from '@/lib/react-query/query-keys'
import type {
  ConfirmPaymentRequest,
  ConfirmPaymentResponse,
} from '@/types/payment'

export function useConfirmPayment() {
  const queryClient = useQueryClient()

  return useMutation<
    ConfirmPaymentResponse,
    AxiosError<{ code?: string; message?: string }>,
    ConfirmPaymentRequest
  >({
    mutationFn: (payload) => confirmPayment(payload),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.payments.all })
      queryClient.invalidateQueries({
        queryKey: queryKeys.reservations.detail(variables.reservationId),
      })
    },
  })
}
