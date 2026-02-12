export interface HoldRequest {
  scheduleId: string
  seatIds: string[]
}

export interface HoldResponse {
  reservationId: string
  status: string
  totalAmount: number
  holdExpiresAt: string
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
  scheduleId: string
  seats: {
    total: number
    available: number
    sold: number
    hold: number
  }
  sold: string[]
  hold: string[]
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
