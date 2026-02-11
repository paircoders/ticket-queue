import { Home, Ticket, User } from 'lucide-react'

export interface NavItem {
  label: string
  href: string
  icon: React.ComponentType<{ className?: string }>
  requiresAuth?: boolean
}

export const NAV_ITEMS: NavItem[] = [
  {
    label: '홈',
    href: '/',
    icon: Home,
  },
  {
    label: '공연 목록',
    href: '/events',
    icon: Ticket,
  },
  {
    label: '마이페이지',
    href: '/mypage',
    icon: User,
    requiresAuth: true,
  },
]
