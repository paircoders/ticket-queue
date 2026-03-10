'use client'

import { useState } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { Search } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

const STATUS_OPTIONS = [
  { value: '', label: '전체' },
  { value: 'OPEN', label: '예매중' },
  { value: 'READY', label: '준비중' },
  { value: 'CLOSED', label: '종료' },
  { value: 'CANCELLED', label: '취소' },
]

interface EventFilterProps {
  defaultKeyword?: string
  defaultStatus?: string
}

export function EventFilter({ defaultKeyword = '', defaultStatus = '' }: EventFilterProps) {
  const router = useRouter()
  const searchParams = useSearchParams()
  const [keyword, setKeyword] = useState(defaultKeyword)
  const [status, setStatus] = useState(defaultStatus)

  function handleSearch() {
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

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') handleSearch()
  }

  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center mb-8">
      <Input
        type="text"
        placeholder="공연명, 아티스트 검색"
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        onKeyDown={handleKeyDown}
        className="sm:w-72"
      />

      <select
        value={status}
        onChange={(e) => setStatus(e.target.value)}
        className={cn(
          'border-input h-9 rounded-md border bg-background px-3 py-1 text-sm shadow-xs transition-colors',
          'focus-visible:outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]',
          'sm:w-36',
        )}
      >
        {STATUS_OPTIONS.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>

      <Button onClick={handleSearch} size="md" className="sm:w-auto">
        <Search className="size-4" />
        검색
      </Button>
    </div>
  )
}
