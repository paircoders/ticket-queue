import { render, screen } from '@testing-library/react'

import { Skeleton } from '../skeleton'

describe('Skeleton', () => {
  it('renders with default classes', () => {
    const { container } = render(<Skeleton data-testid="skeleton" />)
    const skeleton = container.querySelector('[data-slot="skeleton"]')

    expect(skeleton).toBeInTheDocument()
    expect(skeleton).toHaveClass('animate-pulse')
    expect(skeleton).toHaveClass('rounded-md')
    expect(skeleton).toHaveClass('bg-muted')
  })

  it('merges custom className with default classes', () => {
    const { container } = render(<Skeleton className="h-10 w-full" data-testid="skeleton" />)
    const skeleton = container.querySelector('[data-slot="skeleton"]')

    expect(skeleton).toHaveClass('animate-pulse')
    expect(skeleton).toHaveClass('rounded-md')
    expect(skeleton).toHaveClass('bg-muted')
    expect(skeleton).toHaveClass('h-10')
    expect(skeleton).toHaveClass('w-full')
  })

  it('has data-slot attribute', () => {
    const { container } = render(<Skeleton />)
    const skeleton = container.querySelector('[data-slot="skeleton"]')

    expect(skeleton).toBeInTheDocument()
    expect(skeleton).toHaveAttribute('data-slot', 'skeleton')
  })

  it('passes through additional props', () => {
    render(<Skeleton data-testid="custom-skeleton" aria-label="Loading content" />)
    const skeleton = screen.getByTestId('custom-skeleton')

    expect(skeleton).toHaveAttribute('aria-label', 'Loading content')
  })
})
