import { handleApiError } from '../error-handler'
import { toast } from 'sonner'

jest.mock('sonner')

// createAxiosError 헬퍼 함수: AxiosError 형태 객체 생성
function createAxiosError(status: number, data?: { code?: string; message?: string }) {
  return {
    isAxiosError: true,
    response: {
      status,
      data,
    },
    config: {},
    toJSON: () => ({}),
  }
}

describe('handleApiError', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  describe('Non-Axios errors', () => {
    it('shows generic error for non-Axios error', () => {
      handleApiError(new Error('Something went wrong'))
      expect(toast.error).toHaveBeenCalledWith('알 수 없는 오류가 발생했습니다.')
    })

    it('shows generic error for string error', () => {
      handleApiError('string error')
      expect(toast.error).toHaveBeenCalledWith('알 수 없는 오류가 발생했습니다.')
    })

    it('shows generic error for null', () => {
      handleApiError(null)
      expect(toast.error).toHaveBeenCalledWith('알 수 없는 오류가 발생했습니다.')
    })

    it('shows generic error for undefined', () => {
      handleApiError(undefined)
      expect(toast.error).toHaveBeenCalledWith('알 수 없는 오류가 발생했습니다.')
    })
  })

  describe('400 Bad Request', () => {
    it('shows server message when available', () => {
      handleApiError(createAxiosError(400, { message: '서버 메시지' }))
      expect(toast.error).toHaveBeenCalledWith('서버 메시지')
    })

    it('shows fallback message when no server message', () => {
      handleApiError(createAxiosError(400))
      expect(toast.error).toHaveBeenCalledWith('잘못된 요청입니다.')
    })
  })

  describe('403 Forbidden', () => {
    it('shows server message when available', () => {
      handleApiError(createAxiosError(403, { message: '접근 거부됨' }))
      expect(toast.error).toHaveBeenCalledWith('접근 거부됨')
    })

    it('shows fallback message when no server message', () => {
      handleApiError(createAxiosError(403))
      expect(toast.error).toHaveBeenCalledWith('권한이 없습니다.')
    })
  })

  describe('404 Not Found', () => {
    it('shows server message when available', () => {
      handleApiError(createAxiosError(404, { message: '찾을 수 없음' }))
      expect(toast.error).toHaveBeenCalledWith('찾을 수 없음')
    })

    it('shows fallback message when no server message', () => {
      handleApiError(createAxiosError(404))
      expect(toast.error).toHaveBeenCalledWith('요청하신 리소스를 찾을 수 없습니다.')
    })
  })

  describe('409 Conflict', () => {
    it('shows specific message for SEAT_ALREADY_HELD', () => {
      handleApiError(createAxiosError(409, { code: 'SEAT_ALREADY_HELD' }))
      expect(toast.error).toHaveBeenCalledWith('이미 선점된 좌석입니다.')
    })

    it('shows specific message for MAX_SEATS_EXCEEDED', () => {
      handleApiError(createAxiosError(409, { code: 'MAX_SEATS_EXCEEDED' }))
      expect(toast.error).toHaveBeenCalledWith('최대 4장까지만 예매할 수 있습니다.')
    })

    it('shows specific message for DUPLICATE_EMAIL', () => {
      handleApiError(createAxiosError(409, { code: 'DUPLICATE_EMAIL' }))
      expect(toast.error).toHaveBeenCalledWith('이미 사용 중인 이메일입니다.')
    })

    it('shows specific message for DUPLICATE_PAYMENT', () => {
      handleApiError(createAxiosError(409, { code: 'DUPLICATE_PAYMENT' }))
      expect(toast.error).toHaveBeenCalledWith('이미 처리된 결제입니다.')
    })

    it('shows server message for unknown conflict code', () => {
      handleApiError(createAxiosError(409, { code: 'UNKNOWN_CODE', message: '커스텀 충돌' }))
      expect(toast.error).toHaveBeenCalledWith('커스텀 충돌')
    })

    it('shows fallback for unknown conflict code without message', () => {
      handleApiError(createAxiosError(409, { code: 'UNKNOWN_CODE' }))
      expect(toast.error).toHaveBeenCalledWith('요청이 충돌했습니다. 다시 시도해주세요.')
    })

    it('shows fallback when no code provided', () => {
      handleApiError(createAxiosError(409))
      expect(toast.error).toHaveBeenCalledWith('요청이 충돌했습니다. 다시 시도해주세요.')
    })
  })

  describe('422 Unprocessable Entity', () => {
    it('shows server message when available', () => {
      handleApiError(createAxiosError(422, { message: '처리 불가' }))
      expect(toast.error).toHaveBeenCalledWith('처리 불가')
    })

    it('shows fallback message when no server message', () => {
      handleApiError(createAxiosError(422))
      expect(toast.error).toHaveBeenCalledWith('요청을 처리할 수 없습니다.')
    })
  })

  describe('429 Too Many Requests', () => {
    it('shows hardcoded message ignoring server message', () => {
      handleApiError(createAxiosError(429, { message: '서버 메시지 무시됨' }))
      expect(toast.error).toHaveBeenCalledWith('요청이 너무 많습니다. 잠시 후 다시 시도해주세요.')
    })
  })

  describe('503 Service Unavailable', () => {
    it('shows hardcoded message', () => {
      handleApiError(createAxiosError(503))
      expect(toast.error).toHaveBeenCalledWith('서비스를 일시적으로 사용할 수 없습니다.')
    })
  })

  describe('5xx Server Errors', () => {
    it('shows generic server error for 500', () => {
      handleApiError(createAxiosError(500))
      expect(toast.error).toHaveBeenCalledWith('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
    })

    it('shows generic server error for 502', () => {
      handleApiError(createAxiosError(502))
      expect(toast.error).toHaveBeenCalledWith('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
    })
  })

  describe('Unknown status codes', () => {
    it('shows server message for unknown status (418)', () => {
      handleApiError(createAxiosError(418, { message: "I'm a teapot" }))
      expect(toast.error).toHaveBeenCalledWith("I'm a teapot")
    })

    it('shows fallback for unknown status without message', () => {
      handleApiError(createAxiosError(418))
      expect(toast.error).toHaveBeenCalledWith('알 수 없는 오류가 발생했습니다.')
    })
  })

  describe('Network errors', () => {
    it('shows fallback for AxiosError without response', () => {
      const error = {
        isAxiosError: true,
        config: {},
        toJSON: () => ({}),
      }
      handleApiError(error)
      expect(toast.error).toHaveBeenCalledWith('알 수 없는 오류가 발생했습니다.')
    })
  })
})
