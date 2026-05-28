'use client'

import { Skeleton } from '@/components/ui/skeleton'
import { useProfile } from '@/hooks/use-profile'

export function UserProfile() {
  const { data: user, isLoading, isError } = useProfile()

  if (isLoading) {
    return (
      <div className="flex items-center gap-4 rounded-lg border bg-card p-6 shadow-sm">
        <Skeleton className="size-16 rounded-full" />
        <div className="flex-1 space-y-2">
          <Skeleton className="h-5 w-32" />
          <Skeleton className="h-4 w-48" />
        </div>
      </div>
    )
  }

  if (isError || !user) {
    return (
      <div
        role="alert"
        className="rounded-lg border border-destructive/30 bg-destructive/5 p-6 text-destructive"
      >
        프로필 정보를 불러오지 못했습니다.
      </div>
    )
  }

  const initial = user.name?.[0] ?? user.email[0]?.toUpperCase()

  return (
    <section
      aria-labelledby="user-profile-heading"
      className="flex items-center gap-4 rounded-lg border bg-card p-6 shadow-sm"
    >
      <h2 id="user-profile-heading" className="sr-only">
        내 프로필
      </h2>
      <div
        aria-hidden="true"
        className="flex size-16 items-center justify-center rounded-full bg-primary text-2xl font-bold text-primary-foreground"
      >
        {initial}
      </div>
      <div className="flex-1">
        <p className="text-lg font-semibold">{user.name}</p>
        <p className="text-sm text-muted-foreground">{user.email}</p>
        <p className="text-xs text-muted-foreground">{user.phone}</p>
      </div>
    </section>
  )
}
