import { Skeleton } from '@/components/ui/skeleton'

export function EventListSkeleton() {
  return (
    <div className="container mx-auto px-4 py-8">
      <div className="mb-8">
        <Skeleton className="h-8 w-48 mb-2" />
        <Skeleton className="h-4 w-96" />
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {Array.from({ length: 6 }).map((_, index) => (
          <div key={index} className="rounded-lg border bg-card overflow-hidden">
            {/* Image */}
            <Skeleton className="h-64 w-full rounded-none" />

            <div className="p-6 space-y-4">
              {/* Title */}
              <Skeleton className="h-6 w-3/4" />

              {/* Subtitle */}
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-2/3" />

              {/* Badges */}
              <div className="flex gap-2 pt-2">
                <Skeleton className="h-6 w-20 rounded-full" />
                <Skeleton className="h-6 w-16 rounded-full" />
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
