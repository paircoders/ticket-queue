import { render, screen } from '@testing-library/react'

jest.mock('@/hooks/use-reservations', () => ({
  useReservations: jest.fn(),
}))

import { ReservationList } from '../ReservationList'
import { useReservations } from '@/hooks/use-reservations'

const sample = {
  reservationId: 'r1',
  eventTitle: '월드투어 in 서울',
  scheduleDate: '2026-06-01T19:00:00',
  status: 'CONFIRMED',
  seats: [
    { seatNumber: 'A1', grade: 'VIP' },
    { seatNumber: 'A2', grade: 'VIP' },
  ],
  paymentAmount: 400_000,
}

describe('ReservationList', () => {
  beforeEach(() => jest.clearAllMocks())

  it('로딩 중에는 스켈레톤을 표시한다', () => {
    jest.mocked(useReservations).mockReturnValue({
      isLoading: true,
      isError: false,
      data: undefined,
    } as any)
    const { container } = render(<ReservationList limit={3} />)
    expect(container.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
  })

  it('데이터가 없으면 빈 메시지를 표시한다', () => {
    jest.mocked(useReservations).mockReturnValue({
      isLoading: false,
      isError: false,
      data: { list: [] },
    } as any)
    render(<ReservationList emptyMessage="비어 있음" />)
    expect(screen.getByText('비어 있음')).toBeInTheDocument()
  })

  it('limit으로 표시 건수를 제한한다', () => {
    jest.mocked(useReservations).mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        list: [
          sample,
          { ...sample, reservationId: 'r2' },
          { ...sample, reservationId: 'r3' },
        ],
      },
    } as any)
    render(<ReservationList limit={2} />)
    expect(screen.getAllByLabelText(/예매 카드/)).toHaveLength(2)
  })

  it('에러 시 alert를 노출한다', () => {
    jest.mocked(useReservations).mockReturnValue({
      isLoading: false,
      isError: true,
      data: undefined,
    } as any)
    render(<ReservationList />)
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})
