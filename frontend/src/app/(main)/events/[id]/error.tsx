'use client'

import { useEffect } from 'react'
import Link from 'next/link'
import { AlertCircle } from 'lucide-react'

import { Button } from '@/components/ui/button'

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  useEffect(() => {
    console.error(error)
  }, [error])

  return (
    <div className="max-w-7xl mx-auto px-6 py-12">
      <div className="flex flex-col items-center text-center space-y-6 max-w-md mx-auto">
        <AlertCircle className="size-16 text-destructive" />
        <div className="space-y-2">
          <h2 className="text-2xl font-bold text-foreground">공연 정보를 불러올 수 없습니다</h2>
          <p className="text-muted-foreground">
            {error.message || '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'}
          </p>
        </div>
        <div className="flex gap-3">
          <Button onClick={reset} size="lg">
            다시 시도
          </Button>
          <Button asChild variant="outline" size="lg">
            <Link href="/events">공연 목록으로 돌아가기</Link>
          </Button>
        </div>
      </div>
    </div>
  )
}
