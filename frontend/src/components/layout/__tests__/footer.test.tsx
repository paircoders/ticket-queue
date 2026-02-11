import { render, screen } from '@testing-library/react'
import { Footer } from '../footer'

describe('Footer', () => {
  it('renders brand section with logo and tagline', () => {
    render(<Footer />)

    expect(screen.getByRole('heading', { name: '티켓큐', level: 2 })).toBeInTheDocument()
    expect(screen.getByText('공정한 티켓팅의 시작')).toBeInTheDocument()
  })

  it('renders quick links section', () => {
    render(<Footer />)

    expect(screen.getByRole('heading', { name: '바로가기', level: 3 })).toBeInTheDocument()

    const homeLink = screen.getByRole('link', { name: '홈' })
    expect(homeLink).toBeInTheDocument()
    expect(homeLink).toHaveAttribute('href', '/')

    const eventsLink = screen.getByRole('link', { name: '공연 목록' })
    expect(eventsLink).toBeInTheDocument()
    expect(eventsLink).toHaveAttribute('href', '/events')

    const mypageLink = screen.getByRole('link', { name: '마이페이지' })
    expect(mypageLink).toBeInTheDocument()
    expect(mypageLink).toHaveAttribute('href', '/mypage')
  })

  it('renders support links section', () => {
    render(<Footer />)

    expect(screen.getByRole('heading', { name: '고객지원', level: 3 })).toBeInTheDocument()

    expect(screen.getByRole('link', { name: '공지사항' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'FAQ' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '문의하기' })).toBeInTheDocument()
  })

  it('renders copyright text', () => {
    render(<Footer />)

    expect(screen.getByText('© 2026 Ticket Queue. All rights reserved.')).toBeInTheDocument()
  })

  it('renders social icons with aria-labels', () => {
    render(<Footer />)

    const githubLink = screen.getByRole('link', { name: 'GitHub' })
    expect(githubLink).toBeInTheDocument()
    expect(githubLink).toHaveAttribute('aria-label', 'GitHub')
  })
})
