import { z } from 'zod'

export const signupInfoSchema = z
  .object({
    email: z.string().email('올바른 이메일 형식을 입력해주세요.'),
    password: z
      .string()
      .min(8, '비밀번호는 최소 8자 이상이어야 합니다.')
      .regex(/[A-Z]/, '영문 대문자를 포함해야 합니다.')
      .regex(/[a-z]/, '영문 소문자를 포함해야 합니다.')
      .regex(/[0-9]/, '숫자를 포함해야 합니다.')
      .regex(/[^A-Za-z0-9]/, '특수문자를 포함해야 합니다.'),
    passwordConfirm: z.string(),
    name: z.string().min(2, '이름은 최소 2자 이상이어야 합니다.'),
    phone: z
      .string()
      .regex(/^010-\d{4}-\d{4}$/, '전화번호 형식이 올바르지 않습니다. (예: 010-1234-5678)'),
  })
  .refine((data) => data.password === data.passwordConfirm, {
    message: '비밀번호가 일치하지 않습니다.',
    path: ['passwordConfirm'],
  })

export type SignupInfoFormValues = z.infer<typeof signupInfoSchema>
