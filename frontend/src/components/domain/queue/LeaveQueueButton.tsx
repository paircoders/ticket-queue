'use client'

import { useState, useRef, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useLeaveQueue } from '@/hooks/use-leave-queue'
import { useQueueStore } from '@/stores/queue-store'

interface LeaveQueueButtonProps {
  scheduleId: string
}

export function LeaveQueueButton({ scheduleId }: LeaveQueueButtonProps) {
  const [isDialogOpen, setIsDialogOpen] = useState(false)
  const router = useRouter()
  const { mutate: leaveQueue, isPending } = useLeaveQueue()
  const clearQueue = useQueueStore((s) => s.clearQueue)

  const triggerButtonRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)

  // Escape 키 닫기 + 포커스 트랩
  useEffect(() => {
    if (!isDialogOpen) return

    const firstFocusable = dialogRef.current?.querySelector<HTMLElement>('button:not([disabled])')
    firstFocusable?.focus()

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setIsDialogOpen(false)
        return
      }
      if (e.key === 'Tab') {
        const focusableElements =
          dialogRef.current?.querySelectorAll<HTMLElement>('button:not([disabled])')
        if (!focusableElements || focusableElements.length === 0) return
        const first = focusableElements[0]
        const last = focusableElements[focusableElements.length - 1]
        if (e.shiftKey) {
          if (document.activeElement === first) {
            e.preventDefault()
            last.focus()
          }
        } else {
          if (document.activeElement === last) {
            e.preventDefault()
            first.focus()
          }
        }
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [isDialogOpen])

  // 닫힐 때 트리거 버튼으로 포커스 복원
  const wasOpenRef = useRef(false)
  useEffect(() => {
    if (wasOpenRef.current && !isDialogOpen) {
      triggerButtonRef.current?.focus()
    }
    wasOpenRef.current = isDialogOpen
  }, [isDialogOpen])

  function handleConfirm() {
    leaveQueue(scheduleId, {
      onSuccess: () => {
        clearQueue()
        router.push('/')
      },
      onSettled: () => setIsDialogOpen(false),
    })
  }

  return (
    <>
      <button
        ref={triggerButtonRef}
        onClick={() => setIsDialogOpen(true)}
        className="w-full py-2 px-4 border border-gray-300 text-gray-700 rounded-lg font-medium hover:bg-gray-50 transition-colors"
      >
        대기열 나가기
      </button>

      {isDialogOpen && (
        <div className="fixed inset-0 z-50">
          {/* Backdrop — decorative overlay */}
          <div
            aria-hidden="true"
            className="absolute inset-0 bg-black/50"
            onClick={() => setIsDialogOpen(false)}
          />

          {/* Dialog centering wrapper — DOM order after backdrop = painted on top */}
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
            <div
              ref={dialogRef}
              className="pointer-events-auto bg-white rounded-xl shadow-xl p-6 w-full max-w-[24rem] mx-4 space-y-4"
              role="dialog"
              aria-modal="true"
              aria-labelledby="leave-dialog-title"
            >
              <h2 id="leave-dialog-title" className="text-lg font-semibold text-gray-900">
                대기열에서 나가시겠습니까?
              </h2>
              <p className="text-sm text-gray-500">
                대기열에서 나가면 현재 순서를 잃게 됩니다. 다시 진입하면 대기열 맨 뒤로 이동합니다.
              </p>
              <div className="flex gap-3">
                <button
                  onClick={() => setIsDialogOpen(false)}
                  disabled={isPending}
                  className="flex-1 py-2 px-4 border border-gray-300 text-gray-700 rounded-lg font-medium hover:bg-gray-50 transition-colors disabled:opacity-50"
                >
                  취소
                </button>
                <button
                  onClick={handleConfirm}
                  disabled={isPending}
                  className="flex-1 py-2 px-4 bg-red-600 text-white rounded-lg font-medium hover:bg-red-700 transition-colors disabled:opacity-50"
                >
                  {isPending ? '처리 중...' : '나가기'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
