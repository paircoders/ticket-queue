import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

jest.mock('next/navigation', () => ({ useRouter: jest.fn() }))

import { PaymentFailed } from '../PaymentFailed'
import { useRouter } from 'next/navigation'

describe('PaymentFailed', () => {
  const mockReplace = jest.fn()

  beforeEach(() => {
    jest.clearAllMocks()
    jest.mocked(useRouter).mockReturnValue({ replace: mockReplace } as any)
  })

  it('reason=PAYMENT_FAILED 일 때 재시도 버튼이 노출되고 결제 페이지로 이동한다', async () => {
    const user = userEvent.setup()
    render(
      <PaymentFailed
        reservationId="res-1"
        scheduleId="sched-1"
        reason="PAYMENT_FAILED"
      />
    )
    expect(screen.getByText(/결제 승인이 실패했습니다/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '다시 시도하기' }))
    expect(mockReplace).toHaveBeenCalledWith('/payment/res-1')
  })

  it('HOLD_EXPIRED 일 때 재시도 버튼은 노출되지 않고 좌석 다시 선택만 제공된다', () => {
    render(
      <PaymentFailed
        reservationId="res-1"
        scheduleId="sched-1"
        reason="HOLD_EXPIRED"
      />
    )
    expect(
      screen.queryByRole('button', { name: '다시 시도하기' })
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: '좌석 다시 선택' })).toBeInTheDocument()
    expect(screen.getByText(/좌석 선점 시간이 만료되어/)).toBeInTheDocument()
  })

  it('message가 직접 주어지면 reason 메시지보다 우선한다', () => {
    render(
      <PaymentFailed
        reservationId="res-1"
        reason="PAYMENT_FAILED"
        message="네트워크 오류로 결제가 중단되었습니다."
      />
    )
    expect(
      screen.getByText('네트워크 오류로 결제가 중단되었습니다.')
    ).toBeInTheDocument()
  })

  it('DUPLICATE_PAYMENT 일 때 재시도 버튼이 노출되지 않는다', () => {
    render(<PaymentFailed reservationId="res-1" reason="DUPLICATE_PAYMENT" />)
    expect(
      screen.queryByRole('button', { name: '다시 시도하기' })
    ).not.toBeInTheDocument()
    expect(screen.getByText('이미 처리된 결제입니다.')).toBeInTheDocument()
  })
})
