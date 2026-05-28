import { z } from 'zod'

export const updateProfileSchema = z.object({
  name: z
    .string()
    .min(2, '이름은 2자 이상이어야 합니다.')
    .max(20, '이름은 20자 이하여야 합니다.'),
  phone: z
    .string()
    .regex(/^01[0-9]-\d{3,4}-\d{4}$/, '전화번호 형식이 올바르지 않습니다. 예) 010-1234-5678'),
})

export type UpdateProfileFormValues = z.infer<typeof updateProfileSchema>

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, '현재 비밀번호를 입력해주세요.'),
    newPassword: z
      .string()
      .min(8, '비밀번호는 8자 이상이어야 합니다.')
      .max(64, '비밀번호는 64자 이하여야 합니다.')
      .regex(/[A-Za-z]/, '영문자를 포함해야 합니다.')
      .regex(/\d/, '숫자를 포함해야 합니다.')
      .regex(/[^A-Za-z0-9]/, '특수문자를 포함해야 합니다.'),
    newPasswordConfirm: z.string(),
  })
  .refine((data) => data.newPassword === data.newPasswordConfirm, {
    message: '새 비밀번호가 일치하지 않습니다.',
    path: ['newPasswordConfirm'],
  })
  .refine((data) => data.currentPassword !== data.newPassword, {
    message: '현재 비밀번호와 다른 비밀번호를 입력해주세요.',
    path: ['newPassword'],
  })

export type ChangePasswordFormValues = z.infer<typeof changePasswordSchema>
