'use client'

import { useEffect } from 'react'
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
    <div className="flex min-h-screen flex-col items-center justify-center bg-background">
      <div className="text-center space-y-6 max-w-md px-6">
        <AlertCircle className="size-16 text-destructive mx-auto" />
        <div className="space-y-2">
          <h2 className="text-2xl font-bold text-foreground">문제가 발생했습니다</h2>
          <p className="text-muted-foreground">
            {error.message || '알 수 없는 오류가 발생했습니다.'}
          </p>
        </div>
        <Button onClick={reset} size="lg">
          다시 시도
        </Button>
      </div>
    </div>
  )
}
