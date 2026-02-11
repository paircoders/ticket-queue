export interface QueueEnterRequest {
  scheduleId: string
}

export interface QueueEnterResponse {
  status: string
  scheduleId: string
  rank: number
  estimatedWaitTime: number
  token: string | null
}

export interface QueueStatusResponse {
  status: string
  rank: number
  estimatedWaitTime: number
  token: string | null
}

export interface QueueLeaveResponse {
  message: string
}
