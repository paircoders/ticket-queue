'use client'

import * as React from 'react'
import { QueryErrorResetBoundary } from '@tanstack/react-query'
import { ErrorBoundary } from 'react-error-boundary'
import { AlertCircle } from 'lucide-react'

import { Button } from '@/components/ui/button'

interface ErrorFallbackProps {
  error: unknown
  resetErrorBoundary: () => void
}

function ErrorFallback({ error, resetErrorBoundary }: ErrorFallbackProps) {
  const errorMessage = error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.'

  return (
    <div className="flex flex-col items-center justify-center py-12 px-6">
      <div className="text-center space-y-6 max-w-md">
        <AlertCircle className="size-12 text-destructive mx-auto" />
        <div className="space-y-2">
          <h3 className="text-xl font-semibold text-foreground">문제가 발생했습니다</h3>
          <p className="text-sm text-muted-foreground">{errorMessage}</p>
        </div>
        <Button onClick={resetErrorBoundary} size="md">
          다시 시도
        </Button>
      </div>
    </div>
  )
}

interface QueryErrorBoundaryProps {
  children: React.ReactNode
}

export function QueryErrorBoundary({ children }: QueryErrorBoundaryProps) {
  return (
    <QueryErrorResetBoundary>
      {({ reset }) => (
        <ErrorBoundary onReset={reset} FallbackComponent={ErrorFallback}>
          {children}
        </ErrorBoundary>
      )}
    </QueryErrorResetBoundary>
  )
}
