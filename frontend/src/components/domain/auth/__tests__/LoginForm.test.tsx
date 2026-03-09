import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// 컴포넌트 import 전에 환경 변수 설정
process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY = 'test-site-key'

jest.mock('next/navigation', () => ({ useRouter: jest.fn() }))
jest.mock('@/hooks/use-login', () => ({ useLogin: jest.fn() }))
jest.mock('sonner', () => ({
  toast: { success: jest.fn(), error: jest.fn(), warning: jest.fn() },
}))
jest.mock('@/lib/auth/cookies', () => ({
  setAccessTokenCookie: jest.fn(),
  setRefreshTokenCookie: jest.fn(),
}))
jest.mock('@/lib/api/auth', () => ({ getMyProfile: jest.fn() }))
jest.mock('@/stores/auth-store', () => ({ useAuthStore: jest.fn() }))

// react-google-recaptcha를 forwardRef mock으로 교체
jest.mock('react-google-recaptcha', () => {
  const React = require('react')
  return React.forwardRef(({ onChange, onExpired, onErrored }: any, ref: any) => {
    React.useImperativeHandle(ref, () => ({ reset: jest.fn() }))
    return (
      <div data-testid="recaptcha">
        <button type="button" onClick={() => onChange?.('test-token')}>
          captcha-complete
        </button>
        <button type="button" data-testid="captcha-expired-btn" onClick={() => onExpired?.()}>
          captcha-expired
        </button>
        <button type="button" data-testid="captcha-error-btn" onClick={() => onErrored?.()}>
          captcha-error
        </button>
      </div>
    )
  })
})

import { LoginForm } from '../LoginForm'
import { useRouter } from 'next/navigation'
import { useLogin } from '@/hooks/use-login'
import { toast } from 'sonner'
import { setAccessTokenCookie, setRefreshTokenCookie } from '@/lib/auth/cookies'
import { getMyProfile } from '@/lib/api/auth'
import { useAuthStore } from '@/stores/auth-store'

const mockLoginResponse = {
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  expiresIn: 3600,
  tokenType: 'Bearer',
}

const mockUser = {
  id: '1',
  email: 'test@example.com',
  name: '테스트',
  phone: '010-0000-0000',
  role: 'USER',
  createdAt: '2024-01-01',
}

describe('LoginForm', () => {
  const mockPush = jest.fn()
  const mockReplace = jest.fn()
  const mockLoginMutate = jest.fn()
  const mockSetAccessToken = jest.fn()
  const mockSetUser = jest.fn()

  beforeEach(() => {
    jest.clearAllMocks()

    jest.mocked(useRouter).mockReturnValue({ push: mockPush, replace: mockReplace } as any)
    jest.mocked(useLogin).mockReturnValue({ mutate: mockLoginMutate, isPending: false } as any)
    jest.mocked(setAccessTokenCookie).mockResolvedValue(undefined)
    jest.mocked(setRefreshTokenCookie).mockResolvedValue(undefined)
    jest.mocked(getMyProfile).mockResolvedValue(mockUser)
    jest.mocked(useAuthStore).mockImplementation((selector: any) =>
      selector({ isAuthenticated: false })
    )
    ;(useAuthStore as any).getState = jest.fn(() => ({
      setAccessToken: mockSetAccessToken,
      setUser: mockSetUser,
    }))
  })

  describe('렌더링', () => {
    it('이메일, 비밀번호, reCAPTCHA, 로그인 버튼을 표시한다', () => {
      render(<LoginForm />)

      expect(screen.getByPlaceholderText('example@email.com')).toBeInTheDocument()
      expect(screen.getByPlaceholderText('비밀번호를 입력하세요')).toBeInTheDocument()
      expect(screen.getByTestId('recaptcha')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
    })

    it('isPending 시 로그인 버튼이 비활성화된다', () => {
      jest.mocked(useLogin).mockReturnValue({ mutate: mockLoginMutate, isPending: true } as any)

      render(<LoginForm />)

      // loading 시 spinner가 추가되어 accessible name이 "로딩 중 로그인"으로 변경됨
      expect(screen.getByRole('button', { name: /로그인/ })).toBeDisabled()
    })
  })

  describe('유효성 검사', () => {
    it('빈 필드 submit 시 유효성 에러를 표시한다', async () => {
      const user = userEvent.setup()
      render(<LoginForm />)

      await user.click(screen.getByRole('button', { name: '로그인' }))

      expect(await screen.findByText('올바른 이메일 형식을 입력해주세요.')).toBeInTheDocument()
      expect(screen.getByText('비밀번호를 입력해주세요.')).toBeInTheDocument()
    })

    it('reCAPTCHA 미완료 시 경고 toast를 표시하고 API를 호출하지 않는다', async () => {
      const user = userEvent.setup()
      render(<LoginForm />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByRole('button', { name: '로그인' }))

      expect(toast.warning).toHaveBeenCalledWith('보안 확인을 완료해주세요.')
      expect(mockLoginMutate).not.toHaveBeenCalled()
    })
  })

  describe('제출', () => {
    it('유효한 입력으로 submit 시 loginMutate를 올바른 payload로 호출한다', async () => {
      const user = userEvent.setup()
      render(<LoginForm />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByText('captcha-complete'))
      await user.click(screen.getByRole('button', { name: '로그인' }))

      expect(mockLoginMutate).toHaveBeenCalledWith(
        { email: 'test@example.com', password: 'password123', recaptchaToken: 'test-token' },
        expect.objectContaining({ onSuccess: expect.any(Function), onError: expect.any(Function) })
      )
    })

    it('로그인 성공 시 쿠키 저장 + Zustand 업데이트 + 홈 리디렉트', async () => {
      const user = userEvent.setup()
      mockLoginMutate.mockImplementation((_data: any, { onSuccess }: any) => {
        onSuccess(mockLoginResponse)
      })

      render(<LoginForm />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByText('captcha-complete'))
      await user.click(screen.getByRole('button', { name: '로그인' }))

      await waitFor(() => {
        expect(setAccessTokenCookie).toHaveBeenCalledWith('access-token')
        expect(setRefreshTokenCookie).toHaveBeenCalledWith('refresh-token')
        expect(mockSetAccessToken).toHaveBeenCalledWith('access-token')
        expect(toast.success).toHaveBeenCalledWith('로그인되었습니다.')
        expect(mockPush).toHaveBeenCalledWith('/')
      })
    })

    it('returnUrl이 있으면 로그인 성공 시 해당 경로로 리디렉트한다', async () => {
      const user = userEvent.setup()
      mockLoginMutate.mockImplementation((_data: any, { onSuccess }: any) => {
        onSuccess(mockLoginResponse)
      })

      render(<LoginForm returnUrl="/reservation/seats/1" />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByText('captcha-complete'))
      await user.click(screen.getByRole('button', { name: '로그인' }))

      await waitFor(() => {
        expect(mockPush).toHaveBeenCalledWith('/reservation/seats/1')
      })
    })
  })

  describe('에러 처리', () => {
    it('401 에러 시 인증 실패 메시지를 표시한다', async () => {
      const user = userEvent.setup()
      const error = Object.assign(new Error('Unauthorized'), {
        isAxiosError: true,
        response: { status: 401, data: { message: 'Unauthorized', code: 'AUTH_FAILED' } },
      })
      mockLoginMutate.mockImplementation((_data: any, { onError }: any) => {
        onError(error)
      })

      render(<LoginForm />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByText('captcha-complete'))
      await user.click(screen.getByRole('button', { name: '로그인' }))

      expect(toast.error).toHaveBeenCalledWith('이메일 또는 비밀번호가 올바르지 않습니다.')
    })

    it('500 에러 시 서버 내부 메시지를 노출하지 않고 고정 메시지를 표시한다', async () => {
      const user = userEvent.setup()
      const error = Object.assign(new Error('Internal Server Error'), {
        isAxiosError: true,
        response: {
          status: 500,
          data: { message: 'DB connection error: ECONNREFUSED', code: 'INTERNAL_ERROR' },
        },
      })
      mockLoginMutate.mockImplementation((_data: any, { onError }: any) => {
        onError(error)
      })

      render(<LoginForm />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByText('captcha-complete'))
      await user.click(screen.getByRole('button', { name: '로그인' }))

      expect(toast.error).toHaveBeenCalledWith(
        '서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.'
      )
      expect(toast.error).not.toHaveBeenCalledWith('DB connection error: ECONNREFUSED')
    })

    it('4xx 에러 시 서버 메시지를 표시한다', async () => {
      const user = userEvent.setup()
      const error = Object.assign(new Error('Bad Request'), {
        isAxiosError: true,
        response: {
          status: 400,
          data: { message: 'reCAPTCHA 검증에 실패했습니다.', code: 'RECAPTCHA_FAILED' },
        },
      })
      mockLoginMutate.mockImplementation((_data: any, { onError }: any) => {
        onError(error)
      })

      render(<LoginForm />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByText('captcha-complete'))
      await user.click(screen.getByRole('button', { name: '로그인' }))

      expect(toast.error).toHaveBeenCalledWith('reCAPTCHA 검증에 실패했습니다.')
    })

    it('비 Axios 에러 시 알 수 없는 오류 메시지를 표시한다', async () => {
      const user = userEvent.setup()
      mockLoginMutate.mockImplementation((_data: any, { onError }: any) => {
        onError(new Error('Network error'))
      })

      render(<LoginForm />)

      await user.type(screen.getByPlaceholderText('example@email.com'), 'test@example.com')
      await user.type(screen.getByPlaceholderText('비밀번호를 입력하세요'), 'password123')
      await user.click(screen.getByText('captcha-complete'))
      await user.click(screen.getByRole('button', { name: '로그인' }))

      expect(toast.error).toHaveBeenCalledWith('알 수 없는 오류가 발생했습니다.')
    })
  })

  describe('인증 가드', () => {
    it('이미 인증된 사용자가 접근하면 홈으로 리디렉트한다', () => {
      jest.mocked(useAuthStore).mockImplementation((selector: any) =>
        selector({ isAuthenticated: true })
      )

      render(<LoginForm />)

      expect(mockReplace).toHaveBeenCalledWith('/')
    })
  })

  describe('reCAPTCHA', () => {
    it('reCAPTCHA 에러 시 오류 toast를 표시한다', async () => {
      const user = userEvent.setup()
      render(<LoginForm />)

      await user.click(screen.getByTestId('captcha-error-btn'))

      expect(toast.error).toHaveBeenCalledWith(
        '보안 확인에 실패했습니다. 페이지를 새로고침해주세요.'
      )
    })
  })
})
