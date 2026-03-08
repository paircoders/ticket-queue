import Link from 'next/link'
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card'
import { LoginForm } from '@/components/domain/auth'

interface LoginPageProps {
  searchParams: Promise<{ returnUrl?: string }>
}

export default async function LoginPage({ searchParams }: LoginPageProps) {
  const { returnUrl } = await searchParams

  // 개선 #2: 오픈 리디렉트 방지 — 상대 경로(/)로 시작하는 경우만 허용
  const safeReturnUrl = returnUrl?.startsWith('/') ? returnUrl : undefined

  return (
    <Card>
      <CardHeader>
        <CardTitle>로그인</CardTitle>
        <CardDescription>이메일과 비밀번호를 입력해주세요.</CardDescription>
      </CardHeader>
      <CardContent>
        <LoginForm returnUrl={safeReturnUrl} />
      </CardContent>
      <CardFooter className="justify-center">
        <Link href="/signup" className="text-sm text-muted-foreground hover:text-foreground transition-colors">
          회원가입
        </Link>
      </CardFooter>
    </Card>
  )
}
