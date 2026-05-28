import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

jest.mock('next/navigation', () => ({ useRouter: jest.fn() }))
jest.mock('@/hooks/use-payment', () => ({ usePayment: jest.fn() }))
jest.mock('@/hooks/use-confirm-payment', () => ({
  useConfirmPayment: jest.fn(),
}))
jest.mock('@/hooks/use-reservation-detail', () => ({
  useReservationDetail: jest.fn(),
}))
jest.mock('@/lib/portone/payment', () => {
  const actual = jest.requireActual('@/lib/portone/payment')
  return {
    ...actual,
    requestPortOnePayment: jest.fn(),
  }
})
jest.mock('@/stores/auth-store', () => ({ useAuthStore: jest.fn() }))
jest.mock('@/stores/reservation-store', () => ({
  useReservationStore: jest.fn(),
}))
jest.mock('sonner', () => ({
  toast: {
    success: jest.fn(),
    error: jest.fn(),
    info: jest.fn(),
    warning: jest.fn(),
  },
}))

import { PaymentWidget } from '../PaymentWidget'
import { useRouter } from 'next/navigation'
import { usePayment } from '@/hooks/use-payment'
import { useConfirmPayment } from '@/hooks/use-confirm-payment'
import { useReservationDetail } from '@/hooks/use-reservation-detail'
import {
  requestPortOnePayment,
  PaymentCancelledError,
} from '@/lib/portone/payment'
import { useAuthStore } from '@/stores/auth-store'
import { useReservationStore } from '@/stores/reservation-store'
import { toast } from 'sonner'

const baseReservation = {
  reservationId: 'res-1',
  eventId: 'sched-1',
  eventTitle: '월드투어 in 서울',
  artist: '아티스트',
  venueName: '잠실주경기장',
  hallName: 'A홀',
  scheduleDate: '2026-06-01T19:00:00',
  status: 'PENDING',
  seats: [
    { seatId: 's1', seatNumber: 'A1', grade: 'VIP', price: 200_000 },
  ],
  totalAmount: 200_000,
  paymentId: '',
  ticketNumber: '',
  qrData: '',
  createdAt: '2026-05-25T00:00:00',
}

const createResponse = {
  paymentId: 'pay-1',
  amount: 200_000,
  storeId: 'store-1',
  channelKey: 'ch-1',
  paymentKey: 'pk-1',
}

describe('PaymentWidget', () => {
  const mockReplace = jest.fn()
  const mockResetReservation = jest.fn()
  let mockCreate: jest.Mock
  let mockConfirm: jest.Mock

  beforeEach(() => {
    jest.clearAllMocks()

    mockCreate = jest.fn().mockResolvedValue(createResponse)
    mockConfirm = jest.fn().mockResolvedValue({
      paymentId: 'pay-1',
      status: 'PAID',
      paidAt: '2026-05-25T01:00:00',
    })

    jest.mocked(useRouter).mockReturnValue({ replace: mockReplace } as any)
    jest.mocked(usePayment).mockReturnValue({
      mutateAsync: mockCreate,
      isPending: false,
    } as any)
    jest.mocked(useConfirmPayment).mockReturnValue({
      mutateAsync: mockConfirm,
      isPending: false,
    } as any)
    jest.mocked(useReservationDetail).mockReturnValue({
      data: baseReservation,
    } as any)
    jest.mocked(useAuthStore).mockImplementation((selector: any) =>
      selector({
        user: {
          id: 'u1',
          email: 'me@example.com',
          name: '홍길동',
          phone: '010-0000-0000',
          role: 'USER',
          createdAt: '',
        },
      })
    )
    jest.mocked(useReservationStore).mockImplementation((selector: any) =>
      selector({ resetReservation: mockResetReservation })
    )
    ;(requestPortOnePayment as jest.Mock).mockResolvedValue({
      paymentId: 'pay-1',
      transactionId: 'tx-1',
    })
  })

  it('3단계 결제 플로우(생성→위젯→승인)를 순서대로 호출하고 완료 페이지로 이동한다', async () => {
    const user = userEvent.setup()
    render(<PaymentWidget reservationId="res-1" scheduleId="sched-1" />)

    await user.click(screen.getByRole('button', { name: /결제 진행/ }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalledTimes(1))
    expect(mockCreate).toHaveBeenCalledWith({
      reservationId: 'res-1',
      amount: 200_000,
      paymentMethod: 'CARD',
    })

    await waitFor(() => expect(requestPortOnePayment).toHaveBeenCalledTimes(1))
    expect(requestPortOnePayment).toHaveBeenCalledWith(
      expect.objectContaining({
        storeId: 'store-1',
        channelKey: 'ch-1',
        paymentId: 'pk-1',
        orderName: '월드투어 in 서울',
        totalAmount: 200_000,
      })
    )

    await waitFor(() => expect(mockConfirm).toHaveBeenCalledTimes(1))
    expect(mockConfirm).toHaveBeenCalledWith({
      reservationId: 'res-1',
      paymentId: 'pay-1',
      paymentKey: 'pk-1',
      transactionId: 'tx-1',
      amount: 200_000,
    })

    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith(
        '/payment/complete?reservationId=res-1'
      )
    )
    expect(toast.success).toHaveBeenCalledWith('결제가 완료되었습니다.')
    expect(mockResetReservation).toHaveBeenCalled()
  })

  it('PortOne 위젯을 사용자가 취소하면 info toast만 노출하고 승인은 호출되지 않는다', async () => {
    ;(requestPortOnePayment as jest.Mock).mockRejectedValueOnce(
      new PaymentCancelledError()
    )
    const user = userEvent.setup()
    render(<PaymentWidget reservationId="res-1" scheduleId="sched-1" />)

    await user.click(screen.getByRole('button', { name: /결제 진행/ }))

    await waitFor(() => expect(toast.info).toHaveBeenCalledWith('결제를 취소했습니다.'))
    expect(mockConfirm).not.toHaveBeenCalled()
    expect(mockReplace).not.toHaveBeenCalled()
  })

  it('HOLD_EXPIRED 에러 시 reset + 좌석 선택 페이지로 redirect 한다', async () => {
    const expiredError = Object.assign(new Error('hold expired'), {
      isAxiosError: true,
      response: { data: { code: 'HOLD_EXPIRED' } },
    })
    mockConfirm.mockRejectedValueOnce(expiredError)
    const user = userEvent.setup()
    render(<PaymentWidget reservationId="res-1" scheduleId="sched-1" />)

    await user.click(screen.getByRole('button', { name: /결제 진행/ }))

    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith('/reservation/sched-1')
    )
    expect(mockResetReservation).toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('좌석 선점 시간이 만료되었습니다.')
  })
})
