'use client'

import { useMutation } from '@tanstack/react-query'
import type { AxiosError } from 'axios'
import { changePassword } from '@/lib/api/auth'
import type { ChangePasswordRequest } from '@/types/auth'

export function useChangePassword() {
  return useMutation<
    void,
    AxiosError<{ code?: string; message?: string }>,
    ChangePasswordRequest
  >({
    mutationFn: (payload) => changePassword(payload),
  })
}
