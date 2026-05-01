'use client'

import Link from 'next/link'
import type { CSSProperties } from 'react'

interface PaginationProps {
  currentPage: number
  totalPages: number
  keyword?: string
  status?: string
  basePath?: string
}

export function Pagination({ currentPage, totalPages, keyword, status, basePath = '/events' }: PaginationProps) {
  if (totalPages <= 1) return null

  function createPageUrl(p: number) {
    const params = new URLSearchParams()
    if (keyword) params.set('keyword', keyword)
    if (status) params.set('status', status)
    params.set('page', String(p))
    return basePath + '?' + params.toString()
  }

  const pages: (number | 'ellipsis')[] = []
  const windowStart = Math.max(2, currentPage - 2)
  const windowEnd = Math.min(totalPages - 1, currentPage + 2)

  pages.push(1)
  if (windowStart > 2) pages.push('ellipsis')
  for (let i = windowStart; i <= windowEnd; i++) pages.push(i)
  if (windowEnd < totalPages - 1) pages.push('ellipsis')
  pages.push(totalPages)

  const navItemBase: CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    width: '36px',
    height: '36px',
    borderRadius: '9999px',
    fontFamily: 'var(--font-sans)',
    fontSize: '14px',
    fontWeight: 400,
    letterSpacing: '-0.224px',
    transition: 'background-color 0.15s ease, color 0.15s ease',
    textDecoration: 'none',
  }

  return (
    <nav aria-label="페이지 네비게이션" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px', marginTop: '48px' }}>
      {currentPage === 1 ? (
        <span style={{ ...navItemBase, color: 'var(--apple-ink-muted-48)', opacity: 0.4, cursor: 'default' }}>
          ←
        </span>
      ) : (
        <Link
          href={createPageUrl(currentPage - 1)}
          style={{ ...navItemBase, color: 'var(--apple-primary)' }}
        >
          ←
        </Link>
      )}

      {pages.map((page, idx) =>
        page === 'ellipsis' ? (
          <span key={`ellipsis-${idx}`} style={{ ...navItemBase, color: 'var(--apple-ink-muted-48)', cursor: 'default' }}>
            …
          </span>
        ) : page === currentPage ? (
          <span
            key={page}
            aria-current="page"
            style={{
              ...navItemBase,
              backgroundColor: 'var(--apple-primary)',
              color: '#ffffff',
              fontWeight: 600,
            }}
          >
            {page}
          </span>
        ) : (
          <Link
            key={page}
            href={createPageUrl(page)}
            style={{ ...navItemBase, color: 'var(--apple-primary)' }}
          >
            {page}
          </Link>
        ),
      )}

      {currentPage === totalPages ? (
        <span style={{ ...navItemBase, color: 'var(--apple-ink-muted-48)', opacity: 0.4, cursor: 'default' }}>
          →
        </span>
      ) : (
        <Link
          href={createPageUrl(currentPage + 1)}
          style={{ ...navItemBase, color: 'var(--apple-primary)' }}
        >
          →
        </Link>
      )}
    </nav>
  )
}
