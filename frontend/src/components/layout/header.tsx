'use client'

import { useState } from 'react'
import Link from 'next/link'
import { Menu } from 'lucide-react'
import { Navigation } from './navigation'
import { MobileNav } from './mobile-nav'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/hooks/use-auth'

export function Header() {
  const [isMobileNavOpen, setIsMobileNavOpen] = useState(false)
  const { isAuthenticated, user } = useAuth()

  return (
    <header className="sticky top-0 z-40 h-16 border-b border-border bg-background">
      <div className="max-w-7xl mx-auto px-6 h-full flex items-center justify-between">
        {/* Logo */}
        <Link href="/" className="text-lg font-semibold text-foreground hover:text-foreground/80 transition-colors">
          티켓큐
        </Link>

        {/* Desktop Navigation */}
        <div className="hidden md:flex">
          <Navigation orientation="horizontal" />
        </div>

        {/* Desktop Auth Section */}
        <div className="hidden md:flex items-center gap-4">
          {isAuthenticated ? (
            <span className="text-sm text-foreground">
              안녕하세요, {user?.name || '사용자'}님
            </span>
          ) : (
            <>
              <Button variant="ghost" asChild>
                <Link href="/login">로그인</Link>
              </Button>
              <Button asChild>
                <Link href="/signup">회원가입</Link>
              </Button>
            </>
          )}
        </div>

        {/* Mobile Hamburger */}
        <Button
          variant="ghost"
          size="sm"
          className="md:hidden"
          onClick={() => setIsMobileNavOpen(true)}
          aria-label="메뉴 열기"
          aria-expanded={isMobileNavOpen}
        >
          <Menu className="h-6 w-6" />
        </Button>
      </div>

      <MobileNav isOpen={isMobileNavOpen} onClose={() => setIsMobileNavOpen(false)} />
    </header>
  )
}
