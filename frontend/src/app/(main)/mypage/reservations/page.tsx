'use client'

import { ReservationList } from '@/components/domain/mypage/ReservationList'

export default function MyReservationsPage() {
  return (
    <main className="mx-auto max-w-4xl px-spacing-md py-spacing-xl">
      <header className="mb-spacing-lg">
        <h1 className="text-3xl font-bold text-gray-900">예매 내역</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          전체 예매 내역을 확인할 수 있습니다.
        </p>
      </header>

      <ReservationList />
    </main>
  )
}
