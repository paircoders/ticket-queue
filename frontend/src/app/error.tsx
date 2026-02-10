'use client'

import { useEffect } from 'react'

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
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-50">
      <div className="text-center">
        <h2 className="text-2xl font-bold text-gray-900 mb-4">오류가 발생했습니다</h2>
        <p className="text-gray-600 mb-6">{error.message || '알 수 없는 오류가 발생했습니다.'}</p>
        <button
          onClick={reset}
          className="px-6 py-2 bg-primary-500 text-white rounded-md hover:bg-primary-600 transition-colors"
        >
          다시 시도
        </button>
      </div>
    </div>
  )
}
