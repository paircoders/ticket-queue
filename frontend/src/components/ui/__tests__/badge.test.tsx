import { render, screen } from '@testing-library/react'
import { Badge } from '../badge'

describe('Badge', () => {
  it('renders children', () => {
    render(<Badge>New</Badge>)
    expect(screen.getByText('New')).toBeInTheDocument()
  })

  describe('variants', () => {
    it.each(['default', 'secondary', 'outline', 'success', 'warning', 'danger'] as const)(
      'renders %s variant',
      (variant) => {
        render(<Badge variant={variant}>Badge</Badge>)
        expect(screen.getByText('Badge')).toHaveAttribute('data-variant', variant)
      },
    )

    it('defaults to default variant', () => {
      render(<Badge>Badge</Badge>)
      expect(screen.getByText('Badge')).toHaveAttribute('data-variant', 'default')
    })
  })

  it('applies custom className', () => {
    render(<Badge className="custom">Badge</Badge>)
    expect(screen.getByText('Badge')).toHaveClass('custom')
  })
})
