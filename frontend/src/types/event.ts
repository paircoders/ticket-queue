export interface EventSummary {
  id: string
  title: string
  artist: string
  venueName: string
  startDate: string
  endDate: string
  status: string
}

export interface EventDetail {
  id: string
  title: string
  artist: string
  description: string
  venue: { id: string; name: string }
  halls: { id: string; name: string }
  schedules: ScheduleDate[]
}

export interface ScheduleDate {
  date: string
  isSoldOut: boolean
  times: ScheduleTime[]
}

export interface ScheduleTime {
  id: string
  sequence: number
  time: string
  saleStartAt: string
  saleEndAt: string
  status: string
}

export interface Seat {
  id: string
  seatNumber: string
  status: string
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
}
