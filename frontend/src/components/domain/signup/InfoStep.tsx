'use client'

import { useRouter } from 'next/navigation'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import axios from 'axios'
import { FormInput } from '@/components/ui/form-input'
import { Button } from '@/components/ui/button'
import { useSignup } from '@/hooks/use-signup'
import {
  signupInfoSchema,
  type SignupInfoFormValues,
} from '@/lib/validation/signup'
import type { ApiErrorResponse } from '@/types/api'

interface InfoStepProps {
  recaptchaToken: string
  identityVerificationId: string
  onBack: () => void
  onGoToCaptcha: () => void
}

export function InfoStep({
  recaptchaToken,
  identityVerificationId,
  onBack,
  onGoToCaptcha,
}: InfoStepProps) {
  const router = useRouter()
  const { mutate: signupMutate, isPending } = useSignup()

  const {
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<SignupInfoFormValues>({
    resolver: zodResolver(signupInfoSchema),
    defaultValues: {
      email: '',
      password: '',
      passwordConfirm: '',
      name: '',
      phone: '',
    },
  })

  function onSubmit(values: SignupInfoFormValues) {
    signupMutate(
      {
        email: values.email,
        password: values.password,
        name: values.name,
        phone: values.phone,
        identityVerificationId,
        recaptchaToken,
      },
      {
        onSuccess: () => {
          toast.success('회원가입이 완료되었습니다. 로그인해주세요.')
          router.push('/login')
        },
        onError: (error) => {
          if (!axios.isAxiosError(error)) {
            toast.error('알 수 없는 오류가 발생했습니다.')
            return
          }

          const status = error.response?.status
          const data = error.response?.data as ApiErrorResponse | undefined
          const code = data?.code
          const message = data?.message

          if (status === 409) {
            if (code === 'ALREADY_EXISTS_EMAIL' || code === 'DUPLICATE_EMAIL') {
              setError('email', {
                message: '이미 사용 중인 이메일입니다.',
              })
              toast.error('이미 사용 중인 이메일입니다.')
              return
            }
            if (code === 'DUPLICATE_IDENTITY') {
              toast.error('이미 가입된 본인인증 정보입니다.')
              return
            }
          }

          if (status === 400) {
            if (code === 'RECAPTCHA_FAILED') {
              toast.error('보안 인증에 실패했습니다. 다시 시도해주세요.')
              onGoToCaptcha()
              return
            }
            if (
              code === 'PORTONE_VERIFICATION_FAILED' ||
              code === 'PORTONE_VERIFICATION_TIMEOUT'
            ) {
              toast.error(
                code === 'PORTONE_VERIFICATION_TIMEOUT'
                  ? '본인인증 시간이 초과되었습니다. 다시 인증해주세요.'
                  : '본인인증 정보가 유효하지 않습니다. 다시 인증해주세요.',
              )
              onBack()
              return
            }
          }

          if (status === 502) {
            toast.error('일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.')
            return
          }

          toast.error(message ?? '회원가입 중 오류가 발생했습니다.')
        },
      },
    )
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-foreground">정보 입력</h2>
        <p className="text-sm text-muted-foreground mt-1">
          계정 정보를 입력해주세요.
        </p>
      </div>

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
              placeholder="영문 대소문자, 숫자, 특수문자 포함 8자 이상"
              autoComplete="new-password"
              error={errors.password?.message}
              value={field.value}
              onChange={field.onChange}
            />
          )}
        />

        <Controller
          name="passwordConfirm"
          control={control}
          render={({ field }) => (
            <FormInput
              label="비밀번호 확인"
              type="password"
              placeholder="비밀번호를 다시 입력해주세요"
              autoComplete="new-password"
              error={errors.passwordConfirm?.message}
              value={field.value}
              onChange={field.onChange}
            />
          )}
        />

        <Controller
          name="name"
          control={control}
          render={({ field }) => (
            <FormInput
              label="이름"
              type="text"
              placeholder="홍길동"
              autoComplete="name"
              error={errors.name?.message}
              value={field.value}
              onChange={field.onChange}
            />
          )}
        />

        <Controller
          name="phone"
          control={control}
          render={({ field }) => (
            <FormInput
              label="전화번호"
              type="tel"
              placeholder="010-1234-5678"
              autoComplete="tel"
              error={errors.phone?.message}
              value={field.value}
              onChange={field.onChange}
            />
          )}
        />

        <div className="flex gap-3 pt-2">
          <Button
            type="button"
            variant="outline"
            className="flex-1"
            onClick={onBack}
            disabled={isPending}
          >
            이전
          </Button>
          <Button type="submit" className="flex-1" loading={isPending}>
            가입하기
          </Button>
        </div>
      </form>
    </div>
  )
}
