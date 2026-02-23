'use client'

import { useMutation } from '@tanstack/react-query'
import { signup } from '@/lib/api/auth'
import type { SignupRequest, SignupResponse } from '@/types/auth'

export function useSignup() {
  return useMutation<SignupResponse, Error, SignupRequest>({
    mutationFn: signup,
  })
}
