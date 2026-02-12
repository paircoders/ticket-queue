import { apiClient } from './axios'
import type { PaginatedResponse } from '@/types/api'
import type {
  CreatePaymentRequest,
  CreatePaymentResponse,
  ConfirmPaymentRequest,
  ConfirmPaymentResponse,
  PaymentSummary,
  PaymentDetail,
} from '@/types/payment'

export async function createPayment(
  data: CreatePaymentRequest
): Promise<CreatePaymentResponse> {
  const response = await apiClient.post<CreatePaymentResponse>(
    '/payments',
    data
  )
  return response.data
}

export async function confirmPayment(
  data: ConfirmPaymentRequest
): Promise<ConfirmPaymentResponse> {
  const response = await apiClient.post<ConfirmPaymentResponse>(
    '/payments/confirm',
    data
  )
  return response.data
}

export async function getPayments(): Promise<
  PaginatedResponse<PaymentSummary>
> {
  const response =
    await apiClient.get<PaginatedResponse<PaymentSummary>>('/payments')
  return response.data
}

export async function getPaymentDetail(id: string): Promise<PaymentDetail> {
  const response = await apiClient.get<PaymentDetail>(`/payments/${id}`)
  return response.data
}
