'use client'

import { use } from 'react'
import { QueueStatus, QueueInfo, LeaveQueueButton } from '@/components/domain/queue'

export default function QueuePage({ params }: { params: Promise<{ scheduleId: string }> }) {
  const { scheduleId } = use(params)

  return (
    <main className="min-h-[calc(100vh-theme(spacing.32))] flex flex-col justify-center p-6">
      <div className="w-full max-w-[32rem] mx-auto space-y-8">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-gray-900">대기열</h1>
          <p className="mt-1 text-sm text-gray-500">잠시만 기다려 주세요. 곧 입장 가능합니다.</p>
        </div>

        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-6">
          <QueueStatus scheduleId={scheduleId} />
        </div>

        <div className="bg-gray-50 rounded-xl p-4">
          <QueueInfo />
        </div>

        <LeaveQueueButton scheduleId={scheduleId} />
      </div>
    </main>
  )
}
