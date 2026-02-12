export interface ApiErrorResponse {
  code: string
  message: string
  timestamp: string
  traceId: string
}

export interface PaginatedResponse<T> {
  list: T[]
  page: number
  size: number
  totalElements: number
}
