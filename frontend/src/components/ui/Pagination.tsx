'use client'

import Link from 'next/link'
import { cn } from '@/lib/utils'

interface PaginationProps {
  currentPage: number
  totalPages: number
  createPageUrl: (page: number) => string
}

export function Pagination({ currentPage, totalPages, createPageUrl }: PaginationProps) {
  if (totalPages <= 1) return null

  const pages: (number | 'ellipsis')[] = []

  // 페이지 윈도우 계산: 현재 페이지 ±2, 첫/마지막 페이지
  const windowStart = Math.max(2, currentPage - 2)
  const windowEnd = Math.min(totalPages - 1, currentPage + 2)

  pages.push(1)

  if (windowStart > 2) pages.push('ellipsis')

  for (let i = windowStart; i <= windowEnd; i++) {
    pages.push(i)
  }

  if (windowEnd < totalPages - 1) pages.push('ellipsis')

  if (totalPages > 1) pages.push(totalPages)

  return (
    <nav aria-label="페이지 네비게이션" className="flex items-center justify-center gap-1 mt-8">
      {currentPage === 1 ? (
        <span
          aria-disabled="true"
          className="inline-flex h-9 w-9 items-center justify-center rounded-md border text-sm transition-colors pointer-events-none border-border text-muted-foreground opacity-50"
        >
          ←
        </span>
      ) : (
        <Link
          href={createPageUrl(currentPage - 1)}
          className="inline-flex h-9 w-9 items-center justify-center rounded-md border text-sm transition-colors border-border bg-background hover:bg-accent hover:text-accent-foreground"
        >
          ←
        </Link>
      )}

      {pages.map((page, idx) =>
        page === 'ellipsis' ? (
          <span key={`ellipsis-${idx}`} className="inline-flex h-9 w-9 items-center justify-center text-sm text-muted-foreground">
            …
          </span>
        ) : page === currentPage ? (
          <span
            key={page}
            aria-current="page"
            className="inline-flex h-9 w-9 items-center justify-center rounded-md border text-sm font-medium transition-colors border-primary bg-primary text-primary-foreground"
          >
            {page}
          </span>
        ) : (
          <Link
            key={page}
            href={createPageUrl(page)}
            className="inline-flex h-9 w-9 items-center justify-center rounded-md border text-sm font-medium transition-colors border-border bg-background hover:bg-accent hover:text-accent-foreground"
          >
            {page}
          </Link>
        ),
      )}

      {currentPage === totalPages ? (
        <span
          aria-disabled="true"
          className="inline-flex h-9 w-9 items-center justify-center rounded-md border text-sm transition-colors pointer-events-none border-border text-muted-foreground opacity-50"
        >
          →
        </span>
      ) : (
        <Link
          href={createPageUrl(currentPage + 1)}
          className="inline-flex h-9 w-9 items-center justify-center rounded-md border text-sm transition-colors border-border bg-background hover:bg-accent hover:text-accent-foreground"
        >
          →
        </Link>
      )}
    </nav>
  )
}
