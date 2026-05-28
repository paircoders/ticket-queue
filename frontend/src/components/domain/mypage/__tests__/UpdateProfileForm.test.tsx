import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

jest.mock('@/hooks/use-profile', () => ({ useProfile: jest.fn() }))
jest.mock('@/hooks/use-update-profile', () => ({
  useUpdateProfile: jest.fn(),
}))
jest.mock('sonner', () => ({
  toast: { success: jest.fn(), error: jest.fn() },
}))

import { UpdateProfileForm } from '../UpdateProfileForm'
import { useProfile } from '@/hooks/use-profile'
import { useUpdateProfile } from '@/hooks/use-update-profile'
import { toast } from 'sonner'

const baseProfile = {
  id: 'u1',
  email: 'me@example.com',
  name: '홍길동',
  phone: '010-1234-5678',
  role: 'USER',
  createdAt: '2026-01-01',
}

describe('UpdateProfileForm', () => {
  let mockMutate: jest.Mock

  beforeEach(() => {
    jest.clearAllMocks()
    mockMutate = jest.fn()
    jest.mocked(useProfile).mockReturnValue({
      data: baseProfile,
      isLoading: false,
    } as any)
    jest.mocked(useUpdateProfile).mockReturnValue({
      mutate: mockMutate,
      isPending: false,
    } as any)
  })

  it('프로필 로드 후 폼이 기본값으로 채워지고 저장 버튼이 비활성화된다', async () => {
    render(<UpdateProfileForm />)
    await waitFor(() => {
      expect(screen.getByLabelText('이름')).toHaveValue('홍길동')
    })
    expect(screen.getByLabelText('전화번호')).toHaveValue('010-1234-5678')
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()
  })

  it('이름을 변경하면 mutate를 호출하고 성공 toast를 띄운다', async () => {
    mockMutate.mockImplementation((_payload, opts) => {
      opts?.onSuccess?.({ id: 'u1', name: '김길동', phone: '010-1234-5678' })
    })
    const user = userEvent.setup()
    render(<UpdateProfileForm />)
    await waitFor(() =>
      expect(screen.getByLabelText('이름')).toHaveValue('홍길동')
    )

    await user.clear(screen.getByLabelText('이름'))
    await user.type(screen.getByLabelText('이름'), '김길동')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() =>
      expect(mockMutate).toHaveBeenCalledWith(
        { name: '김길동', phone: '010-1234-5678' },
        expect.any(Object)
      )
    )
    expect(toast.success).toHaveBeenCalledWith('프로필이 수정되었습니다.')
  })

  it('전화번호 형식이 잘못되면 검증 에러를 노출하고 mutate를 호출하지 않는다', async () => {
    const user = userEvent.setup()
    render(<UpdateProfileForm />)
    await waitFor(() =>
      expect(screen.getByLabelText('전화번호')).toHaveValue('010-1234-5678')
    )

    await user.clear(screen.getByLabelText('전화번호'))
    await user.type(screen.getByLabelText('전화번호'), '0101234')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(
      await screen.findByText(/전화번호 형식이 올바르지 않습니다/)
    ).toBeInTheDocument()
    expect(mockMutate).not.toHaveBeenCalled()
  })
})
