'use client'

import { Suspense } from 'react'
import { useSearchParams } from 'next/navigation'
import { PaymentComplete } from '@/components/domain/payment/PaymentComplete'

export default function PaymentCompletePage() {
  return (
    <main className="mx-auto flex min-h-[70vh] max-w-2xl flex-col items-center justify-center px-spacing-md py-spacing-xl">
      <Suspense fallback={null}>
        <PaymentCompleteContent />
      </Suspense>
    </main>
  )
}

function PaymentCompleteContent() {
  const params = useSearchParams()
  const reservationId = params.get('reservationId')
  return <PaymentComplete reservationId={reservationId} />
}
