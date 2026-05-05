import { render, screen } from '@testing-library/react'
import { EventCard } from '@/components/domain/event/EventCard'
import type { EventSummary } from '@/types/event'

const baseEvent: EventSummary = {
  id: 'evt-1',
  title: 'BTS World Tour',
  artist: 'BTS',
  venueName: 'KSPO Dome',
  startDate: '2026-06-15',
  endDate: '2026-06-20',
  status: 'OPEN',
}

describe('EventCard', () => {
  it('renders event title', () => {
    render(<EventCard event={baseEvent} />)
    expect(screen.getByRole('heading', { level: 3 })).toHaveTextContent('BTS World Tour')
  })

  it('renders artist and venue name', () => {
    render(<EventCard event={baseEvent} />)
    expect(screen.getAllByText('BTS').length).toBeGreaterThan(0)
    expect(screen.getByText('KSPO Dome')).toBeInTheDocument()
  })

  it('renders the OPEN status label as 예매중', () => {
    render(<EventCard event={baseEvent} />)
    expect(screen.getByText('예매중')).toBeInTheDocument()
  })

  it('renders PREPARING status label as 준비중', () => {
    render(<EventCard event={{ ...baseEvent, status: 'PREPARING' }} />)
    expect(screen.getByText('준비중')).toBeInTheDocument()
  })

  it('renders unknown status value as-is', () => {
    render(<EventCard event={{ ...baseEvent, status: 'UNKNOWN_STATUS' }} />)
    expect(screen.getByText('UNKNOWN_STATUS')).toBeInTheDocument()
  })

  describe('formatDateRange UTC safety', () => {
    it('formats a date range correctly without timezone shift', () => {
      render(<EventCard event={baseEvent} />)
      expect(screen.getByText('2026.06.15 – 2026.06.20')).toBeInTheDocument()
    })

    it('shows a single date when startDate equals endDate', () => {
      render(<EventCard event={{ ...baseEvent, startDate: '2026-06-15', endDate: '2026-06-15' }} />)
      expect(screen.getByText('2026.06.15')).toBeInTheDocument()
    })

    it('does not show the previous day for UTC midnight boundary dates (e.g. 2026-01-01)', () => {
      // new Date('2026-01-01') parses as UTC midnight — in UTC-1 and below getDate() returns 31 Dec 2025
      render(<EventCard event={{ ...baseEvent, startDate: '2026-01-01', endDate: '2026-01-01' }} />)
      expect(screen.getByText('2026.01.01')).toBeInTheDocument()
      expect(screen.queryByText(/2025\.12\.31/)).not.toBeInTheDocument()
    })
  })

  describe('link accessibility', () => {
    it('navigates to the correct event URL', () => {
      render(<EventCard event={baseEvent} />)
      expect(screen.getByRole('link')).toHaveAttribute('href', '/events/evt-1')
    })

    it('link element has focus-visible ring classes for keyboard accessibility', () => {
      render(<EventCard event={baseEvent} />)
      const link = screen.getByRole('link')
      expect(link.className).toMatch(/focus-visible:ring-2/)
      expect(link.className).toMatch(/focus-visible:ring-\[var\(--apple-primary-focus\)\]/)
      expect(link.className).not.toMatch(/\bfocus:outline-none\b/)
    })
  })
})
