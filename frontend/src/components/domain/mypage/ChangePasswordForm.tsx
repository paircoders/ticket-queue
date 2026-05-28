'use client'

import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { FormInput } from '@/components/ui/form-input'
import { useChangePassword } from '@/hooks/use-change-password'
import {
  changePasswordSchema,
  type ChangePasswordFormValues,
} from '@/lib/validation/profile'

export function ChangePasswordForm() {
  const { mutate: changePassword, isPending } = useChangePassword()

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: {
      currentPassword: '',
      newPassword: '',
      newPasswordConfirm: '',
    },
  })

  function onSubmit(values: ChangePasswordFormValues) {
    changePassword(
      {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      },
      {
        onSuccess: () => {
          toast.success('비밀번호가 변경되었습니다.')
          reset()
        },
        onError: (error) => {
          const status = error.response?.status
          if (status === 401) {
            toast.error('현재 비밀번호가 올바르지 않습니다.')
            return
          }
          toast.error('비밀번호 변경에 실패했습니다.')
        },
      }
    )
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="space-y-4 rounded-lg border bg-card p-6 shadow-sm"
      aria-label="비밀번호 변경 폼"
    >
      <h2 className="text-lg font-semibold">비밀번호 변경</h2>

      <Controller
        name="currentPassword"
        control={control}
        render={({ field }) => (
          <FormInput
            label="현재 비밀번호"
            type="password"
            autoComplete="current-password"
            disabled={isPending}
            error={errors.currentPassword?.message}
            value={field.value}
            onChange={field.onChange}
          />
        )}
      />

      <Controller
        name="newPassword"
        control={control}
        render={({ field }) => (
          <FormInput
            label="새 비밀번호"
            type="password"
            autoComplete="new-password"
            disabled={isPending}
            error={errors.newPassword?.message}
            value={field.value}
            onChange={field.onChange}
          />
        )}
      />

      <Controller
        name="newPasswordConfirm"
        control={control}
        render={({ field }) => (
          <FormInput
            label="새 비밀번호 확인"
            type="password"
            autoComplete="new-password"
            disabled={isPending}
            error={errors.newPasswordConfirm?.message}
            value={field.value}
            onChange={field.onChange}
          />
        )}
      />

      <Button type="submit" loading={isPending} className="w-full">
        비밀번호 변경
      </Button>
    </form>
  )
}
