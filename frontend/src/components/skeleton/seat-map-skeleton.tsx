import { Skeleton } from '@/components/ui/skeleton'

export function SeatMapSkeleton() {
  return (
    <div className="container mx-auto px-4 py-8">
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
        {/* Seat Grid */}
        <div className="lg:col-span-3 space-y-6">
          {/* Title */}
          <div className="space-y-2">
            <Skeleton className="h-8 w-48" />
            <Skeleton className="h-4 w-64" />
          </div>

          {/* Stage */}
          <div className="flex justify-center">
            <Skeleton className="h-12 w-64 rounded-full" />
          </div>

          {/* Seat Grid (5 rows x 8 columns) */}
          <div className="space-y-3">
            {Array.from({ length: 5 }).map((_, rowIndex) => (
              <div key={rowIndex} className="flex justify-center gap-2">
                {Array.from({ length: 8 }).map((_, seatIndex) => (
                  <Skeleton key={seatIndex} className="h-10 w-10 rounded" />
                ))}
              </div>
            ))}
          </div>

          {/* Legend */}
          <div className="flex items-center justify-center gap-6 pt-6">
            {Array.from({ length: 3 }).map((_, index) => (
              <div key={index} className="flex items-center gap-2">
                <Skeleton className="h-6 w-6 rounded" />
                <Skeleton className="h-4 w-16" />
              </div>
            ))}
          </div>
        </div>

        {/* Summary Sidebar */}
        <div className="lg:col-span-1">
          <div className="rounded-lg border bg-card p-6 space-y-6 sticky top-8">
            {/* Title */}
            <Skeleton className="h-6 w-32" />

            {/* Event Info */}
            <div className="space-y-3">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-3/4" />
            </div>

            <div className="border-t pt-4 space-y-3">
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-2/3" />
            </div>

            {/* Price */}
            <div className="border-t pt-4 space-y-2">
              <div className="flex justify-between">
                <Skeleton className="h-4 w-16" />
                <Skeleton className="h-4 w-24" />
              </div>
              <div className="flex justify-between items-center pt-2">
                <Skeleton className="h-6 w-12" />
                <Skeleton className="h-8 w-28" />
              </div>
            </div>

            {/* Action Button */}
            <Skeleton className="h-11 w-full rounded-md" />
          </div>
        </div>
      </div>
    </div>
  )
}
