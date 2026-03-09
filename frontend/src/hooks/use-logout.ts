'use client'

import { useMutation } from '@tanstack/react-query'
import { logout } from '@/lib/api/auth'

export function useLogout() {
  return useMutation<void, Error, void>({
    mutationFn: logout,
  })
}
