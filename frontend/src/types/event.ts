export interface EventSummary {
  id: string
  title: string
  artist: string
  venueName: string
  posterUrl?: string
  startDate: string | null
  endDate: string | null
  status: string
}

export interface EventDetail {
  id: string
  title: string
  artist: string
  description: string | null
  venueId: string
  venueName: string
  hallId: string
  hallName: string
  status: string
  schedules: ScheduleDate[]
  createdAt: string
  updatedAt: string
}

export interface ScheduleDate {
  date: string | null
  times: ScheduleTime[]
}

export interface ScheduleTime {
  id: string
  playSequence: number
  eventStartAt: string | null
  eventEndAt: string | null
  saleStartAt: string | null
  saleEndAt: string | null
  status: string
  isSoldOut: boolean
}

export interface Seat {
  id: string
  seatNumber: string
  status: 'AVAILABLE' | 'HOLD' | 'SOLD'
  row: number
  col: number
  grade: string
  price: number
}

export interface SeatGrade {
  grade: string
  price: number
  seats: Seat[]
}

export interface SeatsResponse {
  scheduleId: string
  grades: SeatGrade[]
}

export interface EventListParams {
  page?: number
  size?: number
  status?: string
  city?: string
  keyword?: string
}
