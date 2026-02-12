import { Skeleton } from '@/components/ui/skeleton'

export function QueueSkeleton() {
  return (
    <div className="container mx-auto px-4 py-8 max-w-2xl">
      <div className="rounded-lg border bg-card p-8 space-y-8">
        {/* Title */}
        <div className="text-center space-y-3">
          <Skeleton className="h-8 w-48 mx-auto" />
          <Skeleton className="h-4 w-64 mx-auto" />
        </div>

        {/* Progress Bar */}
        <div className="space-y-3">
          <Skeleton className="h-3 w-full rounded-full" />
          <div className="flex justify-between">
            <Skeleton className="h-3 w-16" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>

        {/* Queue Position */}
        <div className="text-center space-y-4 py-8">
          <Skeleton className="h-4 w-32 mx-auto" />
          <Skeleton className="h-16 w-32 mx-auto" />
          <Skeleton className="h-4 w-40 mx-auto" />
        </div>

        {/* Estimated Time */}
        <div className="bg-muted/50 rounded-lg p-6 space-y-3 text-center">
          <Skeleton className="h-4 w-24 mx-auto" />
          <Skeleton className="h-8 w-20 mx-auto" />
        </div>

        {/* Timer */}
        <div className="flex items-center justify-center gap-2 pt-4">
          <Skeleton className="h-12 w-12 rounded-full" />
          <Skeleton className="h-6 w-32" />
        </div>
      </div>
    </div>
  )
}
