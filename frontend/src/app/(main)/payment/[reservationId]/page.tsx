'use client'

import { use } from 'react'

export default function PaymentPage({ params }: { params: Promise<{ reservationId: string }> }) {
  const { reservationId } = use(params)

  return (
    <main className="flex min-h-screen flex-col items-center justify-center p-spacing-xl">
      <div className="max-w-2xl text-center">
        <h1 className="mb-spacing-lg text-4xl font-bold text-gray-900">결제</h1>
        <p className="text-gray-600">
          예매 ID: <span className="font-mono">{reservationId}</span>
        </p>
        <p className="mt-spacing-md text-gray-500">결제를 진행해주세요.</p>
      </div>
    </main>
  )
}
