'use client'

import { useRouter } from 'next/navigation'
import axios from 'axios'
import type { ApiErrorResponse } from '@/types/api'

interface QueueErrorProps {
  error: Error
  scheduleId: string
}

function getErrorMessage(error: Error): { title: string; description: string } {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiErrorResponse | undefined
    const code = data?.code
    const status = error.response?.status

    if (status === 404 || code === 'NOT_IN_QUEUE') {
      return {
        title: '대기열에 없습니다',
        description: '대기열에 등록되어 있지 않습니다. 공연 페이지로 이동하여 대기열에 다시 진입해주세요.',
      }
    }
    if (code === 'ALREADY_APPROVED') {
      return {
        title: '이미 승인된 상태입니다',
        description: '대기열을 통과했습니다. 좌석 선택 페이지로 이동해주세요.',
      }
    }
  }
  return {
    title: '오류가 발생했습니다',
    description: '대기열 상태를 확인하는 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요.',
  }
}

export function QueueError({ error, scheduleId }: QueueErrorProps) {
  const router = useRouter()
  const { title, description } = getErrorMessage(error)

  const isApproved =
    axios.isAxiosError(error) &&
    (error.response?.data as ApiErrorResponse | undefined)?.code === 'ALREADY_APPROVED'

  return (
    <div className="text-center space-y-4">
      <div className="text-4xl">⚠️</div>
      <h2 className="text-xl font-semibold text-gray-900">{title}</h2>
      <p className="text-sm text-gray-500">{description}</p>
      <div className="flex flex-col gap-2">
        {isApproved ? (
          <button
            onClick={() => router.push(`/reservation/${scheduleId}`)}
            className="w-full py-2 px-4 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition-colors"
          >
            좌석 선택 페이지로 이동
          </button>
        ) : (
          <button
            onClick={() => router.push('/')}
            className="w-full py-2 px-4 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition-colors"
          >
            홈으로 이동
          </button>
        )}
        <button
          onClick={() => router.back()}
          className="w-full py-2 px-4 border border-gray-300 text-gray-700 rounded-lg font-medium hover:bg-gray-50 transition-colors"
        >
          뒤로 가기
        </button>
      </div>
    </div>
  )
}
