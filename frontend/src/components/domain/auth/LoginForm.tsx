'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import axios from 'axios'
import { FormInput } from '@/components/ui/form-input'
import { Button } from '@/components/ui/button'
import { useLogin } from '@/hooks/use-login'
import { loginSchema, type LoginFormValues } from '@/lib/validation/auth'
import { setAccessTokenCookie, setRefreshTokenCookie } from '@/lib/auth/cookies'
import { getMyProfile } from '@/lib/api/auth'
import { useAuthStore } from '@/stores/auth-store'
import { RecaptchaWidget, type RecaptchaWidgetHandle } from './RecaptchaWidget'
import type { ApiErrorResponse } from '@/types/api'

interface LoginFormProps {
  returnUrl?: string
}

export function LoginForm({ returnUrl }: LoginFormProps) {
  const router = useRouter()
  const { mutate: loginMutate, isPending } = useLogin()
  const [recaptchaToken, setRecaptchaToken] = useState<string | null>(null)
  const recaptchaRef = useRef<RecaptchaWidgetHandle>(null)
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated)

  // 개선 #4: 이미 인증된 사용자는 홈으로 리디렉트
  useEffect(() => {
    if (isAuthenticated) {
      router.replace('/')
    }
  }, [isAuthenticated, router])

  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      email: '',
      password: '',
    },
  })

  function onSubmit(values: LoginFormValues) {
    if (!recaptchaToken) {
      toast.warning('보안 확인을 완료해주세요.')
      return
    }

    loginMutate(
      { ...values, recaptchaToken },
      {
        onSuccess: async (data) => {
          await setAccessTokenCookie(data.accessToken)
          await setRefreshTokenCookie(data.refreshToken)
          useAuthStore.getState().setAccessToken(data.accessToken)

          try {
            const user = await getMyProfile()
            useAuthStore.getState().setUser(user)
          } catch {
            // 프로필 조회 실패해도 로그인 플로우는 계속 진행
          }

          toast.success('로그인되었습니다.')
          // 개선 #2: returnUrl이 있으면 해당 경로로, 없으면 홈으로
          router.push(returnUrl || '/')
        },
        onError: (error) => {
          recaptchaRef.current?.reset()
          setRecaptchaToken(null)

          if (!axios.isAxiosError(error)) {
            toast.error('알 수 없는 오류가 발생했습니다.')
            return
          }

          const status = error.response?.status
          const data = error.response?.data as ApiErrorResponse | undefined
          const message = data?.message

          if (status === 401) {
            toast.error('이메일 또는 비밀번호가 올바르지 않습니다.')
            return
          }

          // 개선 #1: 5xx 에러 시 내부 서버 메시지 직접 노출 방지
          if (status !== undefined && status >= 500) {
            toast.error('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
            return
          }

          toast.error(message ?? '로그인 중 오류가 발생했습니다.')
        },
      }
    )
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      <Controller
        name="email"
        control={control}
        render={({ field }) => (
          <FormInput
            label="이메일"
            type="email"
            placeholder="example@email.com"
            autoComplete="email"
            error={errors.email?.message}
            value={field.value}
            onChange={field.onChange}
          />
        )}
      />

      <Controller
        name="password"
        control={control}
        render={({ field }) => (
          <FormInput
            label="비밀번호"
            type="password"
            placeholder="비밀번호를 입력하세요"
            autoComplete="current-password"
            error={errors.password?.message}
            value={field.value}
            onChange={field.onChange}
          />
        )}
      />

      <div className="flex justify-center">
        <RecaptchaWidget ref={recaptchaRef} onChange={setRecaptchaToken} />
      </div>

      <Button type="submit" className="w-full" loading={isPending}>
        로그인
      </Button>
    </form>
  )
}
