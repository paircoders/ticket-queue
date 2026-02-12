import { render, screen } from '@testing-library/react'

import {
  EventListSkeleton,
  EventDetailSkeleton,
  QueueSkeleton,
  SeatMapSkeleton,
  PaymentSummarySkeleton,
} from '../index'

describe('Domain Skeleton Components', () => {
  describe('EventListSkeleton', () => {
    it('renders without crashing', () => {
      const { container } = render(<EventListSkeleton />)
      expect(container).toBeInTheDocument()
    })

    it('renders 6 event cards', () => {
      const { container } = render(<EventListSkeleton />)
      const skeletons = container.querySelectorAll('[data-slot="skeleton"]')
      expect(skeletons.length).toBeGreaterThan(0)
    })
  })

  describe('EventDetailSkeleton', () => {
    it('renders without crashing', () => {
      const { container } = render(<EventDetailSkeleton />)
      expect(container).toBeInTheDocument()
    })

    it('renders poster and schedule sections', () => {
      const { container } = render(<EventDetailSkeleton />)
      const skeletons = container.querySelectorAll('[data-slot="skeleton"]')
      expect(skeletons.length).toBeGreaterThan(0)
    })
  })

  describe('QueueSkeleton', () => {
    it('renders without crashing', () => {
      const { container } = render(<QueueSkeleton />)
      expect(container).toBeInTheDocument()
    })

    it('renders queue components', () => {
      const { container } = render(<QueueSkeleton />)
      const skeletons = container.querySelectorAll('[data-slot="skeleton"]')
      expect(skeletons.length).toBeGreaterThan(0)
    })
  })

  describe('SeatMapSkeleton', () => {
    it('renders without crashing', () => {
      const { container } = render(<SeatMapSkeleton />)
      expect(container).toBeInTheDocument()
    })

    it('renders seat grid and sidebar', () => {
      const { container } = render(<SeatMapSkeleton />)
      const skeletons = container.querySelectorAll('[data-slot="skeleton"]')
      // Should have many skeletons for seats (5x8 = 40) + other elements
      expect(skeletons.length).toBeGreaterThan(40)
    })
  })

  describe('PaymentSummarySkeleton', () => {
    it('renders without crashing', () => {
      const { container } = render(<PaymentSummarySkeleton />)
      expect(container).toBeInTheDocument()
    })

    it('renders payment summary sections', () => {
      const { container } = render(<PaymentSummarySkeleton />)
      const skeletons = container.querySelectorAll('[data-slot="skeleton"]')
      expect(skeletons.length).toBeGreaterThan(0)
    })
  })
})
