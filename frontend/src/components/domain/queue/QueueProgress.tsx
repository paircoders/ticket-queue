interface QueueProgressProps {
  position: number
  totalInQueue: number
}

export function QueueProgress({ position, totalInQueue }: QueueProgressProps) {
  const passed = Math.max(0, totalInQueue - position)
  const percentage =
    totalInQueue > 0 ? Math.round((passed / totalInQueue) * 100) : 0

  return (
    <div className="w-full">
      <div className="flex justify-between text-xs text-gray-500 mb-1">
        <span>진행률</span>
        <span>{percentage}%</span>
      </div>
      <div
        role="progressbar"
        aria-valuenow={percentage}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="대기열 진행률"
        className="w-full bg-gray-200 rounded-full h-2.5 overflow-hidden"
      >
        <div
          className="bg-blue-500 h-2.5 rounded-full transition-all duration-500"
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  )
}
