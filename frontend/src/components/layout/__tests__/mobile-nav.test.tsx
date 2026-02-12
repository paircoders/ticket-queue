import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MobileNav } from '../mobile-nav'

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
  Navigation: ({ orientation, onLinkClick }: { orientation: string; onLinkClick: () => void }) => (
    <nav data-testid="navigation" data-orientation={orientation} onClick={onLinkClick}>
      Navigation
    </nav>
  ),
}))

describe('MobileNav', () => {
  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('renders when isOpen is true', () => {
    render(<MobileNav isOpen={true} onClose={jest.fn()} />)

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('티켓큐')).toBeInTheDocument()
  })

  it('does not render when isOpen is false', () => {
    render(<MobileNav isOpen={false} onClose={jest.fn()} />)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('calls onClose when close button is clicked', async () => {
    const user = userEvent.setup()
    const onClose = jest.fn()

    render(<MobileNav isOpen={true} onClose={onClose} />)

    const closeButton = screen.getByRole('button', { name: '닫기' })
    await user.click(closeButton)

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('renders Navigation component in vertical mode', () => {
    render(<MobileNav isOpen={true} onClose={jest.fn()} />)

    const navigation = screen.getByTestId('navigation')
    expect(navigation).toBeInTheDocument()
    expect(navigation).toHaveAttribute('data-orientation', 'vertical')
  })

  it('shows login/signup buttons when not authenticated', () => {
    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: null,
      isAuthenticated: false,
    })

    render(<MobileNav isOpen={true} onClose={jest.fn()} />)

    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '회원가입' })).toBeInTheDocument()
  })

  it('shows user info and logout when authenticated', () => {
    const { useAuth } = require('@/hooks/use-auth')
    useAuth.mockReturnValue({
      user: {
        name: '테스트',
        email: 'test@test.com'
      },
      isAuthenticated: true,
    })

    render(<MobileNav isOpen={true} onClose={jest.fn()} />)

    expect(screen.getByText('테스트')).toBeInTheDocument()
    expect(screen.getByText('test@test.com')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()

    expect(screen.queryByRole('button', { name: '로그인' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '회원가입' })).not.toBeInTheDocument()
  })
})
