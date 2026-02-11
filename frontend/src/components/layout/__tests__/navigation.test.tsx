import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Navigation } from '../navigation'

jest.mock('next/navigation', () => ({
  usePathname: jest.fn(() => '/'),
}))

jest.mock('@/hooks/use-auth', () => ({
  useAuth: jest.fn(() => ({
    user: null,
    isAuthenticated: false,
  })),
}))

describe('Navigation', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('renders all navigation items', () => {
    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: { name: '테스트' },
      isAuthenticated: true,
    })

    render(<Navigation />)

    expect(screen.getByRole('link', { name: /홈/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /공연 목록/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /마이페이지/ })).toBeInTheDocument()
  })

  it('highlights active page with aria-current', () => {
    const { usePathname } = require('next/navigation')
    const { useAuth } = require('@/hooks/use-auth')

    usePathname.mockReturnValue('/events')
    useAuth.mockReturnValue({
      user: null,
      isAuthenticated: false,
    })

    render(<Navigation />)

    const eventsLink = screen.getByRole('link', { name: /공연 목록/ })
    expect(eventsLink).toHaveAttribute('aria-current', 'page')

    const homeLink = screen.getByRole('link', { name: /홈/ })
    expect(homeLink).not.toHaveAttribute('aria-current')
  })

  it('calls onLinkClick when link is clicked', async () => {
    const user = userEvent.setup()
    const onLinkClick = jest.fn()

    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: null,
      isAuthenticated: false,
    })

    render(<Navigation onLinkClick={onLinkClick} />)

    const homeLink = screen.getByRole('link', { name: /홈/ })
    await user.click(homeLink)

    expect(onLinkClick).toHaveBeenCalledTimes(1)
  })

  it('filters out items requiring auth when not authenticated', () => {
    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: null,
      isAuthenticated: false,
    })

    render(<Navigation />)

    expect(screen.getByRole('link', { name: /홈/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /공연 목록/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /마이페이지/ })).not.toBeInTheDocument()
  })

  it('applies correct orientation classes', () => {
    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: null,
      isAuthenticated: false,
    })

    const { rerender } = render(<Navigation orientation="horizontal" />)
    let nav = screen.getByRole('navigation')
    expect(nav).toHaveClass('flex-row')
    expect(nav).not.toHaveClass('flex-col')

    rerender(<Navigation orientation="vertical" />)
    nav = screen.getByRole('navigation')
    expect(nav).toHaveClass('flex-col')
    expect(nav).not.toHaveClass('flex-row')
  })
})
