'use client'

import { X } from 'lucide-react'
import * as Dialog from '@radix-ui/react-dialog'
import { Button } from '@/components/ui/button'
import { Navigation } from './navigation'
import { useAuth } from '@/hooks/use-auth'

interface MobileNavProps {
  isOpen: boolean
  onClose: () => void
}

export function MobileNav({ isOpen, onClose }: MobileNavProps) {
  const { isAuthenticated, user } = useAuth()

  return (
    <Dialog.Root open={isOpen} onOpenChange={onClose}>
      <Dialog.Portal>
        {/* Overlay */}
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/50 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        {/* Sidebar Content */}
        <Dialog.Content
          className="fixed inset-y-0 left-0 z-50 w-[280px] bg-sidebar shadow-2xl focus:outline-none data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:slide-out-to-left data-[state=open]:slide-in-from-left data-[state=closed]:duration-300 data-[state=open]:duration-300"
          aria-describedby={undefined}
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-border/40 px-6 py-4">
            <Dialog.Title className="text-lg font-bold tracking-tight text-foreground">
              티켓큐
            </Dialog.Title>
            <Dialog.Close asChild>
              <button
                type="button"
                aria-label="닫기"
                className="inline-flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <X className="h-5 w-5" />
              </button>
            </Dialog.Close>
          </div>

          {/* Navigation */}
          <div className="flex-1 overflow-y-auto px-4 py-6">
            <Navigation orientation="vertical" onLinkClick={onClose} />
          </div>

          {/* Auth Section */}
          <div className="border-t border-border/40 p-4">
            {isAuthenticated && user ? (
              <div className="space-y-3">
                <div className="rounded-lg bg-accent/50 px-4 py-3">
                  <p className="text-sm font-medium text-foreground">
                    {user.name}
                  </p>
                  <p className="text-xs text-muted-foreground">{user.email}</p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  className="w-full"
                  onClick={onClose}
                >
                  로그아웃
                </Button>
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                <Button
                  variant="primary"
                  size="sm"
                  className="w-full"
                  onClick={onClose}
                >
                  로그인
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="w-full"
                  onClick={onClose}
                >
                  회원가입
                </Button>
              </div>
            )}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
