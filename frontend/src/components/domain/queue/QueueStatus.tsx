'use client'

import { useCallback, useEffect, useState } from 'react'
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

const QUEUE_TTL_MS = 10 * 60 * 1000
const MAX_ACTIVATE_RETRIES = 3

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

  const [initialTotalInQueue, setInitialTotalInQueue] = useState<number | null>(null)

  // scheduleId가 변경됐을 때 Zustand 상태 초기화 (Zustand setter는 effect에서 허용)
  useEffect(() => {
    if (storedScheduleId !== null && storedScheduleId !== scheduleId) {
      clearQueue()
    }
  }, [scheduleId, storedScheduleId, clearQueue])

  // 첫 응답의 rank를 progress 계산용 기준값으로 캡처 (derived state during render)
  // scheduleId가 변경된 경우 reset, 아직 캡처 전이고 data가 있으면 최초 rank 저장
  if (storedScheduleId !== null && storedScheduleId !== scheduleId && initialTotalInQueue !== null) {
    setInitialTotalInQueue(null)
  } else if (data && initialTotalInQueue === null) {
    setInitialTotalInQueue(data.rank)
  }

  // 첫 폴링 시 enteredAt 설정 (persist로 새로고침 대응)
  // 만료된 enteredAt은 리셋 (새로고침 후 10분 이상 경과 방어)
  useEffect(() => {
    if (!data) return
    if (enteredAt === null) {
      setEnteredAt(Date.now(), scheduleId)
    } else if (Date.now() - enteredAt > QUEUE_TTL_MS) {
      setEnteredAt(Date.now(), scheduleId)
    }
  }, [data, enteredAt, scheduleId, setEnteredAt])

  const [activateRetry, setActivateRetry] = useState(0)
  const [activateFailed, setActivateFailed] = useState(false)

  // ACTIVE 전환 감지 → 쿠키 + Zustand 저장 → 리디렉트
  useEffect(() => {
    if (data?.status === 'ACTIVE' && data.token && !activateFailed) {
      const activate = async () => {
        try {
          await setQueueTokenCookie(data.token!)
          setQueueToken(data.token!, scheduleId)
          toast.success('대기열을 통과했습니다!')
          router.push(`/reservation/${scheduleId}`)
        } catch (err) {
          console.error('대기열 활성화 처리 중 오류 발생:', err)
          if (activateRetry + 1 >= MAX_ACTIVATE_RETRIES) {
            setActivateFailed(true)
            toast.error('대기열 통과 처리에 실패했습니다. 아래 버튼을 눌러 다시 시도해 주세요.')
          } else {
            toast.error('처리 중 오류가 발생했습니다. 잠시 후 다시 시도합니다.')
            setTimeout(() => setActivateRetry((c) => c + 1), 2000)
          }
        }
      }
      activate()
    }
  }, [data?.status, data?.token, scheduleId, router, setQueueToken, activateRetry, activateFailed])

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

  const totalInQueue = initialTotalInQueue ?? data.rank

  return (
    <div className="space-y-6">
      <QueuePosition position={data.rank} />
      <EstimatedTime estimatedWaitSeconds={data.estimatedWaitTime} />
      <QueueProgress position={data.rank} totalInQueue={totalInQueue} />
      {enteredAt !== null && (
        <QueueTimer enteredAt={enteredAt} onExpire={handleTimerExpire} />
      )}
      {activateFailed && (
        <div className="text-center space-y-2">
          <p className="text-sm text-red-600">대기열 통과 처리에 실패했습니다.</p>
          <button
            onClick={() => { setActivateFailed(false); setActivateRetry(0) }}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700"
          >
            다시 시도
          </button>
        </div>
      )}
    </div>
  )
}
