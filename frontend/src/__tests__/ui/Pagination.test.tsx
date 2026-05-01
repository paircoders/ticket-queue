import { render, screen } from '@testing-library/react'
import { Pagination } from '@/components/ui/Pagination'

describe('Pagination', () => {
  describe('렌더링 조건', () => {
    it('totalPages가 1이면 null을 반환한다', () => {
      const { container } = render(
        <Pagination currentPage={1} totalPages={1} basePath="/events" />,
      )
      expect(container.firstChild).toBeNull()
    })

    it('totalPages가 0이면 null을 반환한다', () => {
      const { container } = render(
        <Pagination currentPage={1} totalPages={0} basePath="/events" />,
      )
      expect(container.firstChild).toBeNull()
    })

    it('totalPages가 2 이상이면 nav를 렌더링한다', () => {
      render(<Pagination currentPage={1} totalPages={3} basePath="/events" />)
      expect(screen.getByRole('navigation', { name: '페이지 네비게이션' })).toBeInTheDocument()
    })
  })

  describe('이전/다음 버튼', () => {
    it('첫 페이지일 때 이전 화살표는 링크가 아닌 span으로 렌더링된다', () => {
      render(<Pagination currentPage={1} totalPages={5} basePath="/events" />)
      // ← is not a link when on first page
      const prevLink = screen.queryByRole('link', { name: '←' })
      expect(prevLink).toBeNull()
      // it exists as plain text
      expect(screen.getByText('←')).toBeInTheDocument()
    })

    it('첫 페이지가 아닐 때 이전 화살표는 링크로 렌더링된다', () => {
      render(<Pagination currentPage={3} totalPages={5} basePath="/events" />)
      const prevLink = screen.getByRole('link', { name: '←' })
      expect(prevLink).toBeInTheDocument()
      expect(prevLink).toHaveAttribute('href', '/events?page=2')
    })

    it('마지막 페이지일 때 다음 화살표는 링크가 아닌 span으로 렌더링된다', () => {
      render(<Pagination currentPage={5} totalPages={5} basePath="/events" />)
      const nextLink = screen.queryByRole('link', { name: '→' })
      expect(nextLink).toBeNull()
      expect(screen.getByText('→')).toBeInTheDocument()
    })

    it('마지막 페이지가 아닐 때 다음 화살표는 링크로 렌더링된다', () => {
      render(<Pagination currentPage={3} totalPages={5} basePath="/events" />)
      const nextLink = screen.getByRole('link', { name: '→' })
      expect(nextLink).toBeInTheDocument()
      expect(nextLink).toHaveAttribute('href', '/events?page=4')
    })
  })

  describe('페이지 링크 href 검증', () => {
    it('페이지 링크에 ?page=N이 포함된다', () => {
      render(<Pagination currentPage={1} totalPages={5} basePath="/events" />)
      // page 2 should be a link with ?page=2
      const page2Link = screen.getByRole('link', { name: '2' })
      expect(page2Link).toHaveAttribute('href', '/events?page=2')
    })

    it('현재 페이지는 aria-current="page" 속성을 가진다', () => {
      render(<Pagination currentPage={3} totalPages={5} basePath="/events" />)
      const currentPageEl = screen.getByText('3', { selector: '[aria-current="page"]' })
      expect(currentPageEl).toBeInTheDocument()
    })

    it('현재 페이지는 링크가 아닌 span으로 렌더링된다', () => {
      render(<Pagination currentPage={3} totalPages={5} basePath="/events" />)
      // page 3 should not be a link
      const page3Link = screen.queryByRole('link', { name: '3' })
      expect(page3Link).toBeNull()
    })
  })

  describe('keyword/status 파라미터 전파', () => {
    it('keyword가 있으면 페이지 링크에 keyword 파라미터가 포함된다', () => {
      render(
        <Pagination currentPage={1} totalPages={5} basePath="/events" keyword="BTS" />,
      )
      const page2Link = screen.getByRole('link', { name: '2' })
      expect(page2Link.getAttribute('href')).toContain('keyword=BTS')
      expect(page2Link.getAttribute('href')).toContain('page=2')
    })

    it('status가 있으면 페이지 링크에 status 파라미터가 포함된다', () => {
      render(
        <Pagination currentPage={1} totalPages={5} basePath="/events" status="upcoming" />,
      )
      const page2Link = screen.getByRole('link', { name: '2' })
      expect(page2Link.getAttribute('href')).toContain('status=upcoming')
      expect(page2Link.getAttribute('href')).toContain('page=2')
    })

    it('keyword와 status 모두 있으면 링크에 두 파라미터가 모두 포함된다', () => {
      render(
        <Pagination
          currentPage={1}
          totalPages={5}
          basePath="/events"
          keyword="BTS"
          status="upcoming"
        />,
      )
      const page2Link = screen.getByRole('link', { name: '2' })
      const href = page2Link.getAttribute('href') ?? ''
      expect(href).toContain('keyword=BTS')
      expect(href).toContain('status=upcoming')
      expect(href).toContain('page=2')
    })

    it('keyword/status 없으면 링크에 해당 파라미터가 포함되지 않는다', () => {
      render(<Pagination currentPage={1} totalPages={5} basePath="/events" />)
      const page2Link = screen.getByRole('link', { name: '2' })
      const href = page2Link.getAttribute('href') ?? ''
      expect(href).not.toContain('keyword')
      expect(href).not.toContain('status')
    })

    it('이전/다음 화살표 링크에도 keyword/status 파라미터가 포함된다', () => {
      render(
        <Pagination
          currentPage={3}
          totalPages={5}
          basePath="/events"
          keyword="BTS"
          status="upcoming"
        />,
      )
      const prevLink = screen.getByRole('link', { name: '←' })
      const nextLink = screen.getByRole('link', { name: '→' })
      expect(prevLink.getAttribute('href')).toContain('keyword=BTS')
      expect(prevLink.getAttribute('href')).toContain('status=upcoming')
      expect(nextLink.getAttribute('href')).toContain('keyword=BTS')
      expect(nextLink.getAttribute('href')).toContain('status=upcoming')
    })
  })

  describe('basePath 커스터마이징', () => {
    it('기본 basePath는 /events이다', () => {
      render(<Pagination currentPage={1} totalPages={3} />)
      const page2Link = screen.getByRole('link', { name: '2' })
      expect(page2Link.getAttribute('href')).toMatch(/^\/events\?/)
    })

    it('커스텀 basePath가 링크에 반영된다', () => {
      render(<Pagination currentPage={1} totalPages={3} basePath="/concerts" />)
      const page2Link = screen.getByRole('link', { name: '2' })
      expect(page2Link.getAttribute('href')).toMatch(/^\/concerts\?/)
    })
  })
})
