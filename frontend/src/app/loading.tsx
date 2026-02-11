import { Spinner } from '@/components/ui/spinner'

export default function Loading() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="text-center space-y-4">
        <Spinner size="lg" />
        <p className="text-muted-foreground">로딩 중...</p>
      </div>
    </div>
  )
}
