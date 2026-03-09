'use client'

import { useMutation } from '@tanstack/react-query'
import { login } from '@/lib/api/auth'
import type { LoginRequest, LoginResponse } from '@/types/auth'

export function useLogin() {
  return useMutation<LoginResponse, Error, LoginRequest>({
    mutationFn: login,
  })
}
