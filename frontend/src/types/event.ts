export interface EventSummary {
  id: string
  title: string
  artist: string
  venueName: string
  posterUrl?: string
  startDate: string
  endDate: string
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
  date: string
  times: ScheduleTime[]
}

export interface ScheduleTime {
  id: string
  playSequence: number
  eventStartAt: string
  eventEndAt: string
  saleStartAt: string
  saleEndAt: string
  status: string
  isSoldOut: boolean
}

export interface Seat {
  id: string
  seatNumber: string
  status: 'AVAILABLE' | 'SOLD'
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
