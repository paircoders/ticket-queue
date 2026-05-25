'use client'

import Link from 'next/link'
import { CheckCircle2 } from 'lucide-react'
import { Button } from '@/components/ui/button'

interface PaymentCompleteProps {
  reservationId?: string | null
}

export function PaymentComplete({ reservationId }: PaymentCompleteProps) {
  const reservationsHref = reservationId
    ? `/mypage/reservations/${reservationId}`
    : '/mypage/reservations'

  return (
    <section
      role="status"
      aria-live="polite"
      className="mx-auto flex max-w-md flex-col items-center gap-4 rounded-xl border bg-card p-8 text-center shadow-sm"
    >
      <CheckCircle2
        className="size-16 text-success"
        aria-hidden="true"
      />
      <h2 className="text-2xl font-bold">예매가 완료되었습니다!</h2>
      <p className="text-sm text-muted-foreground">
        결제가 정상적으로 처리되었어요. 자세한 예매 내역은 마이페이지에서
        확인하실 수 있습니다.
      </p>
      {reservationId && (
        <p className="text-xs text-muted-foreground">
          예매번호: <span className="font-mono">{reservationId}</span>
        </p>
      )}
      <div className="mt-2 flex w-full flex-col gap-2 sm:flex-row sm:justify-center">
        <Button asChild className="flex-1">
          <Link href={reservationsHref}>예매 내역 확인</Link>
        </Button>
        <Button asChild variant="outline" className="flex-1">
          <Link href="/">홈으로 돌아가기</Link>
        </Button>
      </div>
    </section>
  )
}
