'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { cn } from '@/lib/utils'
import { NAV_ITEMS } from '@/lib/constants/navigation'
import { useAuth } from '@/hooks/use-auth'

interface NavigationProps {
  orientation?: 'horizontal' | 'vertical'
  onLinkClick?: () => void
  className?: string
}

export function Navigation({
  orientation = 'horizontal',
  onLinkClick,
  className,
}: NavigationProps) {
  const pathname = usePathname()
  const { isAuthenticated } = useAuth()

  const visibleItems = NAV_ITEMS.filter(
    (item) => !item.requiresAuth || isAuthenticated
  )

  return (
    <nav
      role="navigation"
      aria-label="메인 네비게이션"
      className={cn(
        'flex',
        orientation === 'horizontal' ? 'flex-row gap-6' : 'flex-col gap-2',
        className
      )}
    >
      {visibleItems.map((item) => {
        const isActive = pathname === item.href
        const Icon = item.icon

        return (
          <Link
            key={item.href}
            href={item.href}
            onClick={onLinkClick}
            aria-current={isActive ? 'page' : undefined}
            className={cn(
              'flex items-center gap-2 transition-colors',
              isActive
                ? 'text-primary font-medium'
                : 'text-muted-foreground hover:text-foreground'
            )}
          >
            <Icon className="w-5 h-5" />
            <span>{item.label}</span>
          </Link>
        )
      })}
    </nav>
  )
}
