'use client'

import { useEffect, useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { Search } from 'lucide-react'

const STATUS_OPTIONS = [
  { value: '', label: '전체' },
  { value: 'OPEN', label: '예매중' },
  { value: 'PREPARING', label: '준비중' },
  { value: 'ENDED', label: '종료' },
  { value: 'CANCELLED', label: '취소' },
]

interface EventFilterProps {
  defaultKeyword?: string
  defaultStatus?: string
}

export function EventFilter({ defaultKeyword = '', defaultStatus = '' }: EventFilterProps) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const [keyword, setKeyword] = useState(() => searchParams.get('keyword') ?? defaultKeyword)
  const [status, setStatus] = useState(() => searchParams.get('status') ?? defaultStatus)

  // searchParams 변경 시 stale state 방지: URL이 바뀌면 state 동기화
  useEffect(() => {
    setKeyword(searchParams.get('keyword') ?? defaultKeyword)
    setStatus(searchParams.get('status') ?? defaultStatus)
  }, [searchParams, defaultKeyword, defaultStatus])

  function handleSearch(e?: React.FormEvent) {
    e?.preventDefault()
    const params = new URLSearchParams(searchParams.toString())
    if (keyword.trim()) {
      params.set('keyword', keyword.trim())
    } else {
      params.delete('keyword')
    }
    if (status) {
      params.set('status', status)
    } else {
      params.delete('status')
    }
    params.delete('page')
    router.push('/events?' + params.toString())
  }

  return (
    <form
      role="search"
      onSubmit={handleSearch}
      style={{ display: 'flex', flexWrap: 'wrap', gap: '12px', alignItems: 'center', marginBottom: '40px' }}
    >
      {/* Apple pill 검색 input */}
      <div style={{ position: 'relative', flex: '1 1 280px', maxWidth: '360px' }}>
        <Search
          style={{
            position: 'absolute',
            left: '16px',
            top: '50%',
            transform: 'translateY(-50%)',
            width: '16px',
            height: '16px',
            color: 'var(--apple-ink-muted-48)',
            pointerEvents: 'none',
          }}
          aria-hidden="true"
        />
        <input
          type="text"
          aria-label="공연명, 아티스트 검색"
          placeholder="공연명, 아티스트 검색"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          className="focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-[var(--apple-primary)]"
          style={{
            width: '100%',
            height: '44px',
            paddingLeft: '40px',
            paddingRight: '20px',
            borderRadius: '9999px',
            border: '1px solid rgba(0,0,0,0.08)',
            backgroundColor: 'var(--apple-canvas)',
            fontFamily: 'var(--font-sans)',
            fontSize: '17px',
            fontWeight: 400,
            lineHeight: 1.47,
            letterSpacing: '-0.374px',
            color: 'var(--apple-ink)',
            boxSizing: 'border-box',
          }}
        />
      </div>

      {/* 상태 필터 pill select */}
      <select
        aria-label="상태 필터"
        value={status}
        onChange={(e) => setStatus(e.target.value)}
        className="focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-[var(--apple-primary)]"
        style={{
          height: '44px',
          padding: '0 20px',
          borderRadius: '9999px',
          border: '1px solid rgba(0,0,0,0.08)',
          backgroundColor: 'var(--apple-canvas)',
          fontFamily: 'var(--font-sans)',
          fontSize: '14px',
          fontWeight: 400,
          letterSpacing: '-0.224px',
          color: 'var(--apple-ink)',
          cursor: 'pointer',
          appearance: 'auto',
        }}
      >
        {STATUS_OPTIONS.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>

      {/* Apple button-primary pill CTA */}
      <button
        type="submit"
        style={{
          height: '44px',
          padding: '0 22px',
          borderRadius: '9999px',
          border: 'none',
          backgroundColor: 'var(--apple-primary)',
          color: '#ffffff',
          fontFamily: 'var(--font-sans)',
          fontSize: '17px',
          fontWeight: 400,
          letterSpacing: '-0.374px',
          cursor: 'pointer',
          display: 'inline-flex',
          alignItems: 'center',
          gap: '8px',
          transition: 'transform 0.1s ease',
        }}
        onMouseDown={(e) => { e.currentTarget.style.transform = 'scale(0.95)' }}
        onMouseUp={(e) => { e.currentTarget.style.transform = 'scale(1)' }}
        onMouseLeave={(e) => { e.currentTarget.style.transform = 'scale(1)' }}
      >
        <Search style={{ width: '16px', height: '16px' }} aria-hidden="true" />
        검색
      </button>
    </form>
  )
}
