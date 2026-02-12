'use client'

// Toggle this to test authenticated/unauthenticated UI states
const MOCK_AUTHENTICATED = false

interface User {
  id: string
  email: string
  name: string
}

interface UseAuthReturn {
  user: User | null
  isAuthenticated: boolean
}

export function useAuth(): UseAuthReturn {
  if (MOCK_AUTHENTICATED) {
    return {
      user: {
        id: '1',
        email: 'user@example.com',
        name: '테스트 사용자',
      },
      isAuthenticated: true,
    }
  }

  return {
    user: null,
    isAuthenticated: false,
  }
}
