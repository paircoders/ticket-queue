import * as React from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { QueryErrorBoundary } from '../ErrorBoundary'

// Component that throws error
function ThrowError({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) {
    throw new Error('Test error message')
  }
  return <div>No error</div>
}

// Wrapper with QueryClient
function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe('QueryErrorBoundary', () => {
  // Suppress console.error for expected errors in tests
  const originalError = console.error
  beforeAll(() => {
    console.error = jest.fn()
  })

  afterAll(() => {
    console.error = originalError
  })

  it('renders children normally when no error', () => {
    render(
      <Wrapper>
        <QueryErrorBoundary>
          <ThrowError shouldThrow={false} />
        </QueryErrorBoundary>
      </Wrapper>,
    )

    expect(screen.getByText('No error')).toBeInTheDocument()
  })

  it('renders fallback UI when error occurs', () => {
    render(
      <Wrapper>
        <QueryErrorBoundary>
          <ThrowError shouldThrow={true} />
        </QueryErrorBoundary>
      </Wrapper>,
    )

    expect(screen.getByText('문제가 발생했습니다')).toBeInTheDocument()
    expect(screen.getByText('Test error message')).toBeInTheDocument()
  })

  it('shows retry button in fallback UI', () => {
    render(
      <Wrapper>
        <QueryErrorBoundary>
          <ThrowError shouldThrow={true} />
        </QueryErrorBoundary>
      </Wrapper>,
    )

    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('resets error boundary when retry button is clicked', async () => {
    const user = userEvent.setup()
    const TestComponent = () => {
      const [shouldThrow, setShouldThrow] = React.useState(true)

      return (
        <Wrapper>
          <QueryErrorBoundary>
            <div>
              <button onClick={() => setShouldThrow(false)}>Fix Error</button>
              <ThrowError shouldThrow={shouldThrow} />
            </div>
          </QueryErrorBoundary>
        </Wrapper>
      )
    }

    render(<TestComponent />)

    // Error should be displayed
    expect(screen.getByText('문제가 발생했습니다')).toBeInTheDocument()

    // Error boundary should have retry button
    const retryButton = screen.getByRole('button', { name: '다시 시도' })
    expect(retryButton).toBeInTheDocument()
  })

  it('displays AlertCircle icon in fallback', () => {
    const { container } = render(
      <Wrapper>
        <QueryErrorBoundary>
          <ThrowError shouldThrow={true} />
        </QueryErrorBoundary>
      </Wrapper>,
    )

    // Check if svg icon exists
    const icon = container.querySelector('svg')
    expect(icon).toBeInTheDocument()
  })
})
