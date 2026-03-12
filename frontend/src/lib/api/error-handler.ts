import axios from 'axios'
import { toast } from 'sonner'
import type { ApiErrorResponse } from '@/types/api'

export function handleApiError(error: unknown): void {
  if (!axios.isAxiosError(error)) {
    toast.error('알 수 없는 오류가 발생했습니다.')
    return
  }

  const status = error.response?.status
  const data = error.response?.data as ApiErrorResponse | undefined
  const code = data?.code
  const message = data?.message

  switch (status) {
    case 400:
      toast.error(message ?? '잘못된 요청입니다.')
      break
    case 403:
      toast.error(message ?? '권한이 없습니다.')
      break
    case 404:
      toast.error(message ?? '요청하신 리소스를 찾을 수 없습니다.')
      break
    case 409:
      handleConflictError(code, message)
      break
    case 422:
      toast.error(message ?? '요청을 처리할 수 없습니다.')
      break
    case 429:
      toast.error('요청이 너무 많습니다. 잠시 후 다시 시도해주세요.')
      break
    case 503:
      handleServiceUnavailableError(code, message)
      break
    default:
      if (status && status >= 500) {
        toast.error('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
      } else {
        toast.error(message ?? '알 수 없는 오류가 발생했습니다.')
      }
      break
  }
}

function handleConflictError(
  code: string | undefined,
  message: string | undefined
): void {
  switch (code) {
    case 'SEAT_ALREADY_HELD':
      toast.error('이미 선점된 좌석입니다.')
      break
    case 'MAX_SEATS_EXCEEDED':
      toast.error('최대 4장까지만 예매할 수 있습니다.')
      break
    case 'ALREADY_EXISTS_EMAIL':
      toast.error('이미 사용 중인 이메일입니다.')
      break
    case 'DUPLICATE_IDENTITY':
      toast.error('이미 가입된 본인인증 정보입니다.')
      break
    case 'DUPLICATE_PAYMENT':
      toast.error('이미 처리된 결제입니다.')
      break
    case 'ALREADY_IN_QUEUE':
      toast.error('이미 대기열에 등록되어 있습니다.')
      break
    case 'ALREADY_APPROVED':
      toast.error('이미 대기열을 통과한 상태입니다.')
      break
    default:
      toast.error(message ?? '요청이 충돌했습니다. 다시 시도해주세요.')
      break
  }
}

function handleServiceUnavailableError(
  code: string | undefined,
  message: string | undefined
): void {
  if (code === 'QUEUE_FULL') {
    toast.error('대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요.')
    return
  }
  toast.error(message ?? '서비스를 일시적으로 사용할 수 없습니다.')
}
