import { render, screen } from '@testing-library/react'

import NotFound from '../not-found'

// Mock next/link
jest.mock('next/link', () => {
  return ({ children, href }: { children: React.ReactNode; href: string }) => {
    return <a href={href}>{children}</a>
  }
})

describe('NotFound Page', () => {
  it('renders 404 text', () => {
    render(<NotFound />)

    expect(screen.getByText('404')).toBeInTheDocument()
  })

  it('renders heading text', () => {
    render(<NotFound />)

    expect(screen.getByText('페이지를 찾을 수 없습니다')).toBeInTheDocument()
  })

  it('renders description text', () => {
    render(<NotFound />)

    expect(
      screen.getByText('요청하신 페이지가 존재하지 않거나 이동되었습니다.'),
    ).toBeInTheDocument()
  })

  it('renders home link button', () => {
    render(<NotFound />)

    const homeLink = screen.getByRole('link', { name: '홈으로 돌아가기' })
    expect(homeLink).toBeInTheDocument()
    expect(homeLink).toHaveAttribute('href', '/')
  })

  it('renders FileQuestion icon', () => {
    const { container } = render(<NotFound />)

    // Check if svg icon exists
    const icon = container.querySelector('svg')
    expect(icon).toBeInTheDocument()
  })

  it('has proper layout classes', () => {
    const { container } = render(<NotFound />)

    const mainContainer = container.firstChild
    expect(mainContainer).toHaveClass('flex')
    expect(mainContainer).toHaveClass('min-h-screen')
    expect(mainContainer).toHaveClass('items-center')
    expect(mainContainer).toHaveClass('justify-center')
  })
})
