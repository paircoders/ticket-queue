'use client'

import { Suspense } from 'react'
import { useSearchParams } from 'next/navigation'
import { PaymentFailed } from '@/components/domain/payment/PaymentFailed'

export default function PaymentFailedPage() {
  return (
    <main className="mx-auto flex min-h-[70vh] max-w-2xl flex-col items-center justify-center px-spacing-md py-spacing-xl">
      <Suspense fallback={null}>
        <PaymentFailedContent />
      </Suspense>
    </main>
  )
}

function PaymentFailedContent() {
  const params = useSearchParams()
  const reservationId = params.get('reservationId')
  const scheduleId = params.get('scheduleId')
  const reason = params.get('reason')
  const message = params.get('message')

  return (
    <PaymentFailed
      reservationId={reservationId}
      scheduleId={scheduleId}
      reason={reason}
      message={message}
    />
  )
}
