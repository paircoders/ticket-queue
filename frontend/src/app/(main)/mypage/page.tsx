'use client'

import Link from 'next/link'
import { Button } from '@/components/ui/button'
import { UserProfile } from '@/components/domain/mypage/UserProfile'
import { ReservationList } from '@/components/domain/mypage/ReservationList'

export default function MyPage() {
  return (
    <main className="mx-auto max-w-4xl px-spacing-md py-spacing-xl">
      <header className="mb-spacing-lg">
        <h1 className="text-3xl font-bold text-gray-900">마이페이지</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          회원 정보와 예매 내역을 관리할 수 있습니다.
        </p>
      </header>

      <div className="space-y-spacing-lg">
        <UserProfile />

        <nav aria-label="마이페이지 메뉴" className="grid gap-3 sm:grid-cols-2">
          <Button asChild variant="outline" size="lg">
            <Link href="/mypage/profile">프로필 관리</Link>
          </Button>
          <Button asChild variant="outline" size="lg">
            <Link href="/mypage/reservations">예매 내역 전체 보기</Link>
          </Button>
        </nav>

        <section aria-labelledby="recent-reservations-heading">
          <div className="mb-3 flex items-end justify-between">
            <h2 id="recent-reservations-heading" className="text-xl font-bold">
              최근 예매 내역
            </h2>
            <Link
              href="/mypage/reservations"
              className="text-sm text-primary hover:underline"
            >
              전체 보기
            </Link>
          </div>
          <ReservationList limit={5} />
        </section>
      </div>
    </main>
  )
}
