'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { toast } from 'sonner'
import type { AxiosError } from 'axios'
import { Button } from '@/components/ui/button'
import { useAuthStore } from '@/stores/auth-store'
import { useReservationStore } from '@/stores/reservation-store'
import { useReservationDetail } from '@/hooks/use-reservation-detail'
import { usePayment } from '@/hooks/use-payment'
import { useConfirmPayment } from '@/hooks/use-confirm-payment'
import {
  PaymentCancelledError,
  PaymentFailedError,
  requestPortOnePayment,
} from '@/lib/portone/payment'
import { ERROR_CODES } from '@/lib/api/error-codes'

interface PaymentWidgetProps {
  reservationId: string
  scheduleId?: string
}

export function PaymentWidget({ reservationId, scheduleId }: PaymentWidgetProps) {
  const router = useRouter()
  const user = useAuthStore((state) => state.user)
  const resetReservation = useReservationStore((state) => state.resetReservation)
  const { data: reservation } = useReservationDetail(reservationId)
  const paymentMutation = usePayment()
  const confirmMutation = useConfirmPayment()
  const [widgetPending, setWidgetPending] = useState(false)

  const isProcessing =
    paymentMutation.isPending || widgetPending || confirmMutation.isPending

  const handlePayment = async () => {
    if (!reservation) {
      toast.error('예매 정보를 불러오는 중입니다. 잠시 후 다시 시도해 주세요.')
      return
    }

    try {
      // 1) 결제 요청 생성
      const createResult = await paymentMutation.mutateAsync({
        reservationId,
        amount: reservation.totalAmount,
        paymentMethod: 'CARD',
      })

      // 2) PortOne 결제 위젯 호출
      setWidgetPending(true)
      const widgetResult = await requestPortOnePayment({
        storeId: createResult.storeId,
        channelKey: createResult.channelKey,
        paymentId: createResult.paymentKey,
        orderName: reservation.eventTitle,
        totalAmount: createResult.amount,
        customerEmail: user?.email,
        customerName: user?.name,
        customerPhone: user?.phone,
      })
      setWidgetPending(false)

      // 3) 결제 승인
      await confirmMutation.mutateAsync({
        reservationId,
        paymentId: createResult.paymentId,
        paymentKey: createResult.paymentKey,
        transactionId: widgetResult.transactionId,
        amount: createResult.amount,
      })

      toast.success('결제가 완료되었습니다.')
      resetReservation()
      router.replace(`/payment/complete?reservationId=${reservationId}`)
    } catch (error) {
      setWidgetPending(false)
      handlePaymentError(error, { router, scheduleId, resetReservation })
    }
  }

  return (
    <div className="rounded-lg border bg-card p-6 shadow-sm">
      <Button
        variant="primary"
        size="lg"
        className="w-full"
        onClick={handlePayment}
        loading={isProcessing}
        disabled={!reservation || isProcessing}
        aria-label="결제 진행"
      >
        {isProcessing ? '결제 진행 중...' : `${(reservation?.totalAmount ?? 0).toLocaleString()}원 결제하기`}
      </Button>
      <p className="mt-3 text-center text-xs text-muted-foreground">
        결제 진행 시 PortOne 결제창이 열립니다. 카드 정보는 PortOne으로 직접
        전달되며 본 서비스에 저장되지 않습니다.
      </p>
    </div>
  )
}

interface ErrorHandlerCtx {
  router: ReturnType<typeof useRouter>
  scheduleId?: string
  resetReservation: () => void
}

function handlePaymentError(error: unknown, ctx: ErrorHandlerCtx): void {
  if (error instanceof PaymentCancelledError) {
    toast.info('결제를 취소했습니다.')
    return
  }

  if (error instanceof PaymentFailedError) {
    toast.error(error.message || '결제에 실패했습니다. 다시 시도해주세요.')
    return
  }

  if (isAxiosError(error)) {
    const code = error.response?.data?.code
    if (code === ERROR_CODES.HOLD_EXPIRED) {
      toast.error('좌석 선점 시간이 만료되었습니다.')
      ctx.resetReservation()
      if (ctx.scheduleId) {
        ctx.router.replace(`/reservation/${ctx.scheduleId}`)
      } else {
        ctx.router.replace('/')
      }
      return
    }
    if (code === ERROR_CODES.RESERVATION_NOT_FOUND) {
      toast.error('예매 정보를 찾을 수 없습니다.')
      ctx.router.replace('/')
      return
    }
    if (code === ERROR_CODES.DUPLICATE_PAYMENT) {
      toast.error('이미 처리된 결제입니다.')
      return
    }
    if (code === ERROR_CODES.PAYMENT_FAILED) {
      toast.error('결제 승인에 실패했습니다.')
      return
    }
    return
  }

  toast.error('결제 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
}

function isAxiosError(
  error: unknown
): error is AxiosError<{ code?: string; message?: string }> {
  return Boolean(
    error &&
      typeof error === 'object' &&
      'isAxiosError' in error &&
      (error as { isAxiosError?: boolean }).isAxiosError
  )
}
