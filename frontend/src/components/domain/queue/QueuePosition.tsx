interface QueuePositionProps {
  position: number
}

export function QueuePosition({ position }: QueuePositionProps) {
  return (
    <div className="text-center">
      <p className="text-sm text-gray-500 mb-1">현재 대기 순서</p>
      <p className="text-6xl font-bold text-gray-900">
        {position.toLocaleString()}
        <span className="text-2xl font-medium text-gray-600 ml-1">번째</span>
      </p>
    </div>
  )
}
