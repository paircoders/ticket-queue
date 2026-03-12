export type QueueStatus = 'WAITING' | 'ACTIVE'

export interface QueueEnterRequest {
  scheduleId: string
}

export interface QueueEnterResponse {
  status: QueueStatus
  scheduleId: string
  rank: number
  estimatedWaitTime: number
  token: string | null
}

export interface QueueStatusResponse {
  status: QueueStatus
  rank: number
  estimatedWaitTime: number
  token: string | null
}

export interface QueueLeaveResponse {
  message: string
}
