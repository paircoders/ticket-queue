export interface ReservationSeat {
  seatId: string
  row: number
  col: number
  grade: string
  price: number
  status: 'AVAILABLE' | 'HOLD' | 'SOLD'
}

export interface HoldRequest {
  scheduleId: string
  seatIds: string[]
}

export interface HoldResponse {
  reservationId: string
  scheduleId: string
  seatIds: string[]
  holdExpiresAt: string
  totalPrice: number
}

export interface UpdateHoldRequest {
  newSeatIds: string[]
}

export interface UpdateHoldResponse {
  reservationId: string
  status: string
  newTotalAmount: number
  holdExpiresAt: string
}

export interface RealtimeSeatsResponse {
  seats: ReservationSeat[]
}

export interface ReservationSeatInfo {
  seatNumber: string
  grade: string
}

export interface ReservationSummary {
  reservationId: string
  eventTitle: string
  scheduleDate: string
  status: string
  seats: ReservationSeatInfo[]
  paymentAmount: number
}

export interface ReservationDetailSeat {
  seatId: string
  seatNumber: string
  grade: string
  price: number
}

export interface ReservationDetail {
  reservationId: string
  eventId: string
  eventTitle: string
  artist: string
  venueName: string
  hallName: string
  scheduleDate: string
  status: string
  seats: ReservationDetailSeat[]
  totalAmount: number
  paymentId: string
  ticketNumber: string
  qrData: string
  createdAt: string
}

export interface CancelReservationResponse {
  id: string
  status: string
  refundAmount: number
}
