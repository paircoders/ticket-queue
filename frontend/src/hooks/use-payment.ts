'use client'

import { useMutation } from '@tanstack/react-query'
import type { AxiosError } from 'axios'
import { createPayment } from '@/lib/api/payments'
import type {
  CreatePaymentRequest,
  CreatePaymentResponse,
} from '@/types/payment'

export function usePayment() {
  return useMutation<
    CreatePaymentResponse,
    AxiosError<{ code?: string; message?: string }>,
    CreatePaymentRequest
  >({
    mutationFn: (payload) => createPayment(payload),
  })
}
