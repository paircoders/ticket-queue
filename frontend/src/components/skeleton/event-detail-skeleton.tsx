import { Skeleton } from '@/components/ui/skeleton'

export function EventDetailSkeleton() {
  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
        {/* Poster Image */}
        <div className="md:col-span-1">
          <Skeleton className="aspect-[3/4] w-full rounded-lg" />
        </div>

        {/* Event Info */}
        <div className="md:col-span-2 space-y-6">
          {/* Title */}
          <div className="space-y-3">
            <Skeleton className="h-10 w-3/4" />
            <Skeleton className="h-6 w-1/2" />
          </div>

          {/* Description */}
          <div className="space-y-2 pt-4">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-3/4" />
          </div>

          {/* Schedule Cards */}
          <div className="space-y-3 pt-6">
            <Skeleton className="h-6 w-32 mb-4" />
            {Array.from({ length: 3 }).map((_, index) => (
              <div
                key={index}
                className="rounded-lg border bg-card p-4 flex items-center justify-between"
              >
                <div className="space-y-2 flex-1">
                  <Skeleton className="h-5 w-48" />
                  <Skeleton className="h-4 w-32" />
                </div>
                <Skeleton className="h-10 w-24" />
              </div>
            ))}
          </div>
        </div>
      </div>
  )
}
