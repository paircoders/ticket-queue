import Link from 'next/link'
import { Github } from 'lucide-react'
import { cn } from '@/lib/utils'

export function Footer() {
  return (
    <footer data-slot="footer" className="border-t border-border bg-background">
      <div className="max-w-7xl mx-auto px-6 py-12">
        {/* 3-Column Grid Layout */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-8 md:gap-12">
          {/* Column 1 - Brand Section */}
          <div>
            <h2 className="text-xl font-bold mb-2">티켓큐</h2>
            <p className="text-sm text-muted-foreground">
              공정한 티켓팅의 시작
            </p>
          </div>

          {/* Column 2 - Quick Links */}
          <div>
            <h3 className="font-semibold mb-4">바로가기</h3>
            <ul className="space-y-3">
              <li>
                <Link
                  href="/"
                  className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  홈
                </Link>
              </li>
              <li>
                <Link
                  href="/events"
                  className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  공연 목록
                </Link>
              </li>
              <li>
                <Link
                  href="/mypage"
                  className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  마이페이지
                </Link>
              </li>
            </ul>
          </div>

          {/* Column 3 - Support Links */}
          <div>
            <h3 className="font-semibold mb-4">고객지원</h3>
            <ul className="space-y-3">
              <li>
                <Link
                  href="#"
                  className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  공지사항
                </Link>
              </li>
              <li>
                <Link
                  href="#"
                  className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  FAQ
                </Link>
              </li>
              <li>
                <Link
                  href="#"
                  className="text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  문의하기
                </Link>
              </li>
            </ul>
          </div>
        </div>

        {/* Bottom Section */}
        <div className="border-t border-border mt-8 pt-8">
          <div className="flex flex-col md:flex-row justify-between items-center gap-4">
            {/* Copyright */}
            <p className="text-sm text-muted-foreground">
              © 2026 Ticket Queue. All rights reserved.
            </p>

            {/* Social Icons */}
            <div className="flex items-center gap-4">
              <Link
                href="#"
                aria-label="GitHub"
                className="text-muted-foreground hover:text-foreground transition-colors"
              >
                <Github className="w-5 h-5" />
              </Link>
            </div>
          </div>
        </div>
      </div>
    </footer>
  )
}
