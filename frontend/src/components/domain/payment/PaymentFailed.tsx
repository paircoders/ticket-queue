'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'

const REASON_MESSAGES: Record<string, string> = {
  HOLD_EXPIRED: '좌석 선점 시간이 만료되어 결제가 취소되었습니다. 좌석을 다시 선택해주세요.',
  USER_CANCEL: '결제를 취소했습니다. 다시 시도하실 수 있습니다.',
  PAYMENT_FAILED: '결제 승인이 실패했습니다. 잠시 후 다시 시도해주세요.',
  DUPLICATE_PAYMENT: '이미 처리된 결제입니다.',
}

interface PaymentFailedProps {
  reservationId?: string | null
  scheduleId?: string | null
  reason?: string | null
  message?: string | null
}

export function PaymentFailed({
  reservationId,
  scheduleId,
  reason,
  message,
}: PaymentFailedProps) {
  const router = useRouter()
  const description =
    message || (reason ? REASON_MESSAGES[reason] : null) ||
    '결제 처리 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.'

  const canRetry =
    reason !== 'HOLD_EXPIRED' && reason !== 'DUPLICATE_PAYMENT'

  return (
    <section
      role="alert"
      aria-live="assertive"
      className="mx-auto flex max-w-md flex-col items-center gap-4 rounded-xl border bg-card p-8 text-center shadow-sm"
    >
      <XCircle className="size-16 text-danger" aria-hidden="true" />
      <h2 className="text-2xl font-bold">결제에 실패했습니다</h2>
      <p className="text-sm text-muted-foreground">{description}</p>

      <div className="mt-2 flex w-full flex-col gap-2 sm:flex-row sm:justify-center">
        {canRetry && reservationId && (
          <Button
            className="flex-1"
            onClick={() => router.replace(`/payment/${reservationId}`)}
          >
            다시 시도하기
          </Button>
        )}
        {scheduleId && (
          <Button
            asChild
            variant={canRetry && reservationId ? 'outline' : 'primary'}
            className="flex-1"
          >
            <Link href={`/reservation/${scheduleId}`}>좌석 다시 선택</Link>
          </Button>
        )}
        <Button asChild variant="ghost" className="flex-1">
          <Link href="/">홈으로</Link>
        </Button>
      </div>
    </section>
  )
}
