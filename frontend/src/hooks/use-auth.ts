'use client'

import { useAuthStore } from '@/stores/auth-store'

export function useAuth() {
  const { user, isAuthenticated } = useAuthStore()
  return { user, isAuthenticated }
}
