'use client'

import { use } from 'react'

export default function QueuePage({ params }: { params: Promise<{ scheduleId: string }> }) {
  const { scheduleId } = use(params)

  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-spacing-xl">
      <div className="max-w-2xl text-center">
        <h1 className="mb-spacing-lg text-4xl font-bold text-gray-900">대기열</h1>
        <p className="text-gray-600">
          회차 ID: <span className="font-mono">{scheduleId}</span>
        </p>
        <p className="mt-spacing-md text-gray-500">대기열에서 순서를 기다리는 중입니다.</p>
      </div>
    </main>
  )
}
