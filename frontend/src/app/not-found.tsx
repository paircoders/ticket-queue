import Link from 'next/link'
import { FileQuestion } from 'lucide-react'

import { Button } from '@/components/ui/button'

export default function NotFound() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background">
      <div className="text-center space-y-6 max-w-md px-6">
        <FileQuestion className="size-16 text-muted-foreground mx-auto" />
        <div className="space-y-2">
          <h1 className="text-8xl font-bold text-foreground">404</h1>
          <h2 className="text-2xl font-semibold text-foreground">페이지를 찾을 수 없습니다</h2>
          <p className="text-muted-foreground">
            요청하신 페이지가 존재하지 않거나 이동되었습니다.
          </p>
        </div>
        <Button asChild size="lg">
          <Link href="/">홈으로 돌아가기</Link>
        </Button>
      </div>
    </div>
  )
}
