'use client'

import { UpdateProfileForm } from '@/components/domain/mypage/UpdateProfileForm'
import { ChangePasswordForm } from '@/components/domain/mypage/ChangePasswordForm'

export default function ProfilePage() {
  return (
    <main className="mx-auto max-w-2xl px-spacing-md py-spacing-xl">
      <header className="mb-spacing-lg">
        <h1 className="text-3xl font-bold text-gray-900">프로필 관리</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          이름, 전화번호, 비밀번호를 변경할 수 있습니다.
        </p>
      </header>

      <div className="space-y-spacing-lg">
        <UpdateProfileForm />
        <ChangePasswordForm />
      </div>
    </main>
  )
}
