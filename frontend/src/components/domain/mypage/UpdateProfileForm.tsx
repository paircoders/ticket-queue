'use client'

import { useEffect } from 'react'
import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { FormInput } from '@/components/ui/form-input'
import { useProfile } from '@/hooks/use-profile'
import { useUpdateProfile } from '@/hooks/use-update-profile'
import {
  updateProfileSchema,
  type UpdateProfileFormValues,
} from '@/lib/validation/profile'

export function UpdateProfileForm() {
  const { data: profile, isLoading } = useProfile()
  const { mutate: update, isPending } = useUpdateProfile()

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<UpdateProfileFormValues>({
    resolver: zodResolver(updateProfileSchema),
    defaultValues: { name: '', phone: '' },
  })

  useEffect(() => {
    if (profile) {
      reset({ name: profile.name, phone: profile.phone })
    }
  }, [profile, reset])

  function onSubmit(values: UpdateProfileFormValues) {
    update(values, {
      onSuccess: (data) => {
        toast.success('프로필이 수정되었습니다.')
        reset({ name: data.name, phone: data.phone })
      },
      onError: () => {
        toast.error('프로필 수정에 실패했습니다.')
      },
    })
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="space-y-4 rounded-lg border bg-card p-6 shadow-sm"
      aria-label="프로필 수정 폼"
    >
      <h2 className="text-lg font-semibold">기본 정보</h2>

      <Controller
        name="name"
        control={control}
        render={({ field }) => (
          <FormInput
            label="이름"
            placeholder="홍길동"
            autoComplete="name"
            disabled={isLoading || isPending}
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
            placeholder="010-1234-5678"
            autoComplete="tel"
            disabled={isLoading || isPending}
            error={errors.phone?.message}
            value={field.value}
            onChange={field.onChange}
          />
        )}
      />

      <Button
        type="submit"
        loading={isPending}
        disabled={!isDirty || isLoading || isPending}
        className="w-full"
      >
        저장
      </Button>
    </form>
  )
}
