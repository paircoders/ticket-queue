'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { toast } from 'sonner'
import { useQueueStatus } from '@/hooks/use-queue-status'
import { useQueueStore } from '@/stores/queue-store'
import { setQueueTokenCookie } from '@/lib/auth/cookies'
import { QueueSkeleton } from './QueueSkeleton'
import { QueueError } from './QueueError'
import { QueuePosition } from './QueuePosition'
import { EstimatedTime } from './EstimatedTime'
import { QueueProgress } from './QueueProgress'
import { QueueTimer } from './QueueTimer'

interface QueueStatusProps {
  scheduleId: string
}

export function QueueStatus({ scheduleId }: QueueStatusProps) {
  const router = useRouter()
  const { data, isLoading, error } = useQueueStatus(scheduleId)

  const enteredAt = useQueueStore((s) => s.enteredAt)
  const storedScheduleId = useQueueStore((s) => s.scheduleId)
  const setEnteredAt = useQueueStore((s) => s.setEnteredAt)
  const clearQueue = useQueueStore((s) => s.clearQueue)
  const setQueueToken = useQueueStore((s) => s.setQueueToken)

  // scheduleId가 변경됐을 때 stale 상태 초기화
  const initialTotalInQueueRef = useRef<number | null>(null)
  useEffect(() => {
    if (storedScheduleId !== null && storedScheduleId !== scheduleId) {
      clearQueue()
      initialTotalInQueueRef.current = null
    }
  }, [scheduleId, storedScheduleId, clearQueue])

  // 첫 응답의 rank를 progress 계산용 기준값으로 캡처
  if (data && initialTotalInQueueRef.current === null) {
    initialTotalInQueueRef.current = data.rank
  }

  // 첫 폴링 시 enteredAt 설정 (persist로 새로고침 대응)
  useEffect(() => {
    if (data && enteredAt === null) {
      setEnteredAt(Date.now(), scheduleId)
    }
  }, [data, enteredAt, scheduleId, setEnteredAt])

  const [activateRetry, setActivateRetry] = useState(0)

  // ACTIVE 전환 감지 → 쿠키 + Zustand 저장 → 리디렉트
  useEffect(() => {
    if (data?.status === 'ACTIVE' && data.token) {
      const activate = async () => {
        try {
          await setQueueTokenCookie(data.token!)
          setQueueToken(data.token!, scheduleId)
          toast.success('대기열을 통과했습니다!')
          router.push(`/reservation/${scheduleId}`)
        } catch (err) {
          console.error('대기열 활성화 처리 중 오류 발생:', err)
          toast.error('처리 중 오류가 발생했습니다. 잠시 후 다시 시도합니다.')
          setTimeout(() => setActivateRetry((c) => c + 1), 2000)
        }
      }
      activate()
    }
  }, [data?.status, data?.token, scheduleId, router, setQueueToken, activateRetry])

  // TTL 만료 시 홈으로 리디렉트
  const handleTimerExpire = useCallback(() => {
    clearQueue()
    toast.warning('대기열 시간이 만료되었습니다. 다시 대기열에 진입해 주세요.')
    router.replace('/')
  }, [clearQueue, router])

  if (isLoading) {
    return <QueueSkeleton />
  }

  if (error) {
    return <QueueError error={error} scheduleId={scheduleId} />
  }

  if (!data) {
    return null
  }

  const totalInQueue = initialTotalInQueueRef.current ?? data.rank

  return (
    <div className="space-y-6">
      <QueuePosition position={data.rank} />
      <EstimatedTime estimatedWaitSeconds={data.estimatedWaitTime} />
      <QueueProgress position={data.rank} totalInQueue={totalInQueue} />
      {enteredAt !== null && (
        <QueueTimer enteredAt={enteredAt} onExpire={handleTimerExpire} />
      )}
    </div>
  )
}
