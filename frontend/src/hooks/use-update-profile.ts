'use client'

import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { AxiosError } from 'axios'
import { updateMyProfile } from '@/lib/api/auth'
import { queryKeys } from '@/lib/react-query/query-keys'
import { useAuthStore } from '@/stores/auth-store'
import type {
  UpdateProfileRequest,
  UpdateProfileResponse,
} from '@/types/auth'

export function useUpdateProfile() {
  const queryClient = useQueryClient()
  const setUser = useAuthStore((state) => state.setUser)
  const user = useAuthStore((state) => state.user)

  return useMutation<
    UpdateProfileResponse,
    AxiosError<{ code?: string; message?: string }>,
    UpdateProfileRequest
  >({
    mutationFn: (payload) => updateMyProfile(payload),
    onSuccess: (data) => {
      if (user) {
        setUser({ ...user, name: data.name, phone: data.phone })
      }
      queryClient.invalidateQueries({ queryKey: queryKeys.user.profile() })
    },
  })
}
