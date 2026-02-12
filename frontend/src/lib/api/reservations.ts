import { apiClient } from './axios'
import type {
  HoldRequest,
  HoldResponse,
  UpdateHoldRequest,
  UpdateHoldResponse,
  RealtimeSeatsResponse,
  ReservationSummary,
  ReservationDetail,
  CancelReservationResponse,
} from '@/types/reservation'

export async function getRealtimeSeats(
  scheduleId: string
): Promise<RealtimeSeatsResponse> {
  const response = await apiClient.get<RealtimeSeatsResponse>(
    `/reservations/seats/${scheduleId}`
  )
  return response.data
}

export async function holdSeats(data: HoldRequest): Promise<HoldResponse> {
  const response = await apiClient.post<HoldResponse>(
    '/reservations/hold',
    data
  )
  return response.data
}

export async function updateHold(
  reservationId: string,
  data: UpdateHoldRequest
): Promise<UpdateHoldResponse> {
  const response = await apiClient.put<UpdateHoldResponse>(
    `/reservations/hold/${reservationId}`,
    data
  )
  return response.data
}

export async function releaseHold(reservationId: string): Promise<void> {
  await apiClient.delete(`/reservations/hold/${reservationId}`)
}

export async function getReservations(): Promise<{
  list: ReservationSummary[]
}> {
  const response =
    await apiClient.get<{ list: ReservationSummary[] }>('/reservations')
  return response.data
}

export async function getReservationDetail(
  id: string
): Promise<ReservationDetail> {
  const response = await apiClient.get<ReservationDetail>(
    `/reservations/${id}`
  )
  return response.data
}

export async function cancelReservation(
  id: string
): Promise<CancelReservationResponse> {
  const response = await apiClient.delete<CancelReservationResponse>(
    `/reservations/${id}`
  )
  return response.data
}
