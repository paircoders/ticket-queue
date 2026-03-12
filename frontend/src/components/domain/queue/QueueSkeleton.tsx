export function QueueSkeleton() {
  return (
    <div className="space-y-6 animate-pulse">
      {/* 순서 */}
      <div className="text-center space-y-2">
        <div className="h-4 w-24 bg-gray-200 rounded mx-auto" />
        <div className="h-16 w-48 bg-gray-200 rounded mx-auto" />
      </div>

      {/* 예상 시간 */}
      <div className="text-center space-y-2">
        <div className="h-4 w-28 bg-gray-200 rounded mx-auto" />
        <div className="h-6 w-20 bg-gray-200 rounded mx-auto" />
      </div>

      {/* 진행률 */}
      <div className="space-y-1">
        <div className="flex justify-between">
          <div className="h-3 w-12 bg-gray-200 rounded" />
          <div className="h-3 w-8 bg-gray-200 rounded" />
        </div>
        <div className="h-2.5 w-full bg-gray-200 rounded-full" />
      </div>

      {/* 타이머 */}
      <div className="text-center space-y-2">
        <div className="h-4 w-20 bg-gray-200 rounded mx-auto" />
        <div className="h-9 w-28 bg-gray-200 rounded mx-auto" />
      </div>
    </div>
  )
}
