import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EventFilter } from '@/components/domain/event/EventFilter'

const mockPush = jest.fn()
const mockSearchParamsGet = jest.fn()
const mockSearchParamsToString = jest.fn(() => '')

// stable reference — if useSearchParams() returns a new object every render,
// useEffect([searchParams]) fires every render and resets state
const stableSearchParams = {
  get: (...args: Parameters<typeof mockSearchParamsGet>) => mockSearchParamsGet(...args),
  toString: () => mockSearchParamsToString(),
}

jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
  useSearchParams: () => stableSearchParams,
}))

describe('EventFilter', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    mockSearchParamsGet.mockReturnValue(null)
    mockSearchParamsToString.mockReturnValue('')
  })

  describe('접근성', () => {
    it('role="search"인 form이 렌더링된다', () => {
      render(<EventFilter />)
      expect(screen.getByRole('search')).toBeInTheDocument()
    })

    it('상태 select에 aria-label이 있다', () => {
      render(<EventFilter />)
      expect(screen.getByRole('combobox', { name: '상태 필터' })).toBeInTheDocument()
    })

    it('검색 input에 aria-label이 있다', () => {
      render(<EventFilter />)
      expect(screen.getByRole('textbox', { name: '공연명, 아티스트 검색' })).toBeInTheDocument()
    })

    it('검색 버튼은 type="submit"이다', () => {
      render(<EventFilter />)
      const btn = screen.getByRole('button', { name: /검색/ })
      expect(btn).toHaveAttribute('type', 'submit')
    })

    it('검색 input에 focus-visible ring 클래스가 있다', () => {
      render(<EventFilter />)
      const input = screen.getByRole('textbox', { name: '공연명, 아티스트 검색' })
      expect(input.className).toContain('focus-visible:ring-2')
      expect(input.className).toContain('focus-visible:ring-offset-2')
    })

    it('상태 select에 focus-visible ring 클래스가 있다', () => {
      render(<EventFilter />)
      const select = screen.getByRole('combobox', { name: '상태 필터' })
      expect(select.className).toContain('focus-visible:ring-2')
      expect(select.className).toContain('focus-visible:ring-offset-2')
    })
  })

  describe('검색 동작', () => {
    it('키워드 입력 후 검색 버튼 클릭 시 router.push가 호출된다', async () => {
      render(<EventFilter />)
      const input = screen.getByRole('textbox', { name: '공연명, 아티스트 검색' })
      await userEvent.type(input, 'BTS')
      fireEvent.click(screen.getByRole('button', { name: /검색/ }))
      expect(mockPush).toHaveBeenCalledWith(expect.stringContaining('keyword=BTS'))
    })

    it('폼 submit 시 router.push가 호출된다', () => {
      render(<EventFilter />)
      const input = screen.getByRole('textbox', { name: '공연명, 아티스트 검색' })
      fireEvent.change(input, { target: { value: 'BTS' } })
      fireEvent.submit(screen.getByRole('search'))
      expect(mockPush).toHaveBeenCalledWith(expect.stringContaining('keyword=BTS'))
    })

    it('키워드가 비어있으면 router.push URL에 keyword가 없다', () => {
      render(<EventFilter />)
      fireEvent.submit(screen.getByRole('search'))
      expect(mockPush).toHaveBeenCalledWith(expect.not.stringContaining('keyword'))
    })

    it('상태 select 변경 후 검색 시 status가 URL에 포함된다', () => {
      render(<EventFilter />)
      const select = screen.getByRole('combobox', { name: '상태 필터' })
      fireEvent.change(select, { target: { value: 'OPEN' } })
      fireEvent.submit(screen.getByRole('search'))
      expect(mockPush).toHaveBeenCalledWith(expect.stringContaining('status=OPEN'))
    })

    it('상태가 비어있으면 router.push URL에 status가 없다', () => {
      render(<EventFilter />)
      fireEvent.submit(screen.getByRole('search'))
      expect(mockPush).toHaveBeenCalledWith(expect.not.stringContaining('status'))
    })

    it('검색 시 page 파라미터가 제거된다', () => {
      mockSearchParamsToString.mockReturnValue('page=3')
      render(<EventFilter defaultKeyword="BTS" />)
      fireEvent.submit(screen.getByRole('search'))
      expect(mockPush).toHaveBeenCalledWith(expect.not.stringContaining('page='))
    })
  })

  describe('defaultKeyword / defaultStatus props', () => {
    it('searchParams에 값이 없으면 defaultKeyword로 초기화된다', () => {
      render(<EventFilter defaultKeyword="아이유" />)
      const input = screen.getByRole('textbox', { name: '공연명, 아티스트 검색' })
      expect(input).toHaveValue('아이유')
    })

    it('searchParams에 값이 없으면 defaultStatus로 초기화된다', () => {
      render(<EventFilter defaultStatus="OPEN" />)
      const select = screen.getByRole('combobox', { name: '상태 필터' })
      expect(select).toHaveValue('OPEN')
    })

    it('searchParams에 keyword 값이 있으면 searchParams 값이 우선된다', () => {
      mockSearchParamsGet.mockImplementation((key: string) => {
        if (key === 'keyword') return 'BTS'
        return null
      })
      render(<EventFilter defaultKeyword="아이유" />)
      const input = screen.getByRole('textbox', { name: '공연명, 아티스트 검색' })
      expect(input).toHaveValue('BTS')
    })
  })

  describe('STATUS_OPTIONS 렌더링', () => {
    it('전체/예매중/준비중/종료/취소 옵션이 모두 렌더링된다', () => {
      render(<EventFilter />)
      expect(screen.getByRole('option', { name: '전체' })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: '예매중' })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: '준비중' })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: '종료' })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: '취소' })).toBeInTheDocument()
    })
  })
})
