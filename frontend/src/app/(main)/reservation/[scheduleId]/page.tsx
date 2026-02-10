'use client'

import { use } from 'react'

export default function ReservationPage({ params }: { params: Promise<{ scheduleId: string }> }) {
  const { scheduleId } = use(params)

  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-spacing-xl">
      <div className="max-w-2xl text-center">
        <h1 className="mb-spacing-lg text-4xl font-bold text-gray-900">좌석 선택</h1>
        <p className="text-gray-600">
          회차 ID: <span className="font-mono">{scheduleId}</span>
        </p>
        <p className="mt-spacing-md text-gray-500">원하는 좌석을 선택해주세요.</p>
      </div>
    </main>
  )
}
