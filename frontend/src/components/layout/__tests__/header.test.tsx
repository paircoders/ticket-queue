import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Header } from '../header'

jest.mock('next/navigation', () => ({
  usePathname: jest.fn(() => '/'),
}))

jest.mock('@/hooks/use-auth', () => ({
  useAuth: jest.fn(() => ({
    user: null,
    isAuthenticated: false,
  })),
}))

jest.mock('../navigation', () => ({
  Navigation: ({ orientation }: { orientation: string }) => (
    <nav data-testid="navigation" data-orientation={orientation} />
  ),
}))

jest.mock('../mobile-nav', () => ({
  MobileNav: ({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) => (
    <div data-testid="mobile-nav" data-open={isOpen} onClick={onClose} />
  ),
}))

describe('Header', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('renders logo with link to home', () => {
    render(<Header />)
    const logo = screen.getByText('티켓큐')
    expect(logo).toBeInTheDocument()
    expect(logo.closest('a')).toHaveAttribute('href', '/')
  })

  it('shows desktop navigation on large screens', () => {
    render(<Header />)
    const nav = screen.getByTestId('navigation')
    expect(nav).toBeInTheDocument()
    expect(nav).toHaveAttribute('data-orientation', 'horizontal')
    expect(nav.parentElement).toHaveClass('hidden', 'md:flex')
  })

  it('shows hamburger menu button on mobile', () => {
    render(<Header />)
    const hamburger = screen.getByRole('button', { name: '메뉴 열기' })
    expect(hamburger).toBeInTheDocument()
    expect(hamburger).toHaveAttribute('aria-label', '메뉴 열기')
  })

  it('toggles mobile navigation on hamburger click', async () => {
    const user = userEvent.setup()
    render(<Header />)

    const hamburger = screen.getByRole('button', { name: '메뉴 열기' })
    expect(hamburger).toHaveAttribute('aria-expanded', 'false')

    await user.click(hamburger)

    expect(hamburger).toHaveAttribute('aria-expanded', 'true')
    const mobileNav = screen.getByTestId('mobile-nav')
    expect(mobileNav).toHaveAttribute('data-open', 'true')
  })

  it('shows login/signup buttons when not authenticated', () => {
    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: null,
      isAuthenticated: false,
    })

    render(<Header />)

    expect(screen.getByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '회원가입' })).toBeInTheDocument()
  })

  it('shows user greeting when authenticated', () => {
    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: { name: '테스트' },
      isAuthenticated: true,
    })

    render(<Header />)

    expect(screen.getByText('안녕하세요, 테스트님')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '로그인' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '회원가입' })).not.toBeInTheDocument()
  })
})
