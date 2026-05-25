'use client'

import { useQuery } from '@tanstack/react-query'
import { getMyProfile } from '@/lib/api/auth'
import { queryKeys } from '@/lib/react-query/query-keys'

export function useProfile() {
  return useQuery({
    queryKey: queryKeys.user.profile(),
    queryFn: getMyProfile,
    staleTime: 60_000,
  })
}
