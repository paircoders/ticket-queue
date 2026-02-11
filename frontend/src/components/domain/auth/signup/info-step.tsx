'use client';

import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import type { SignupRequest } from '@/types/auth';
import type { SignupFormData } from '@/types/signup';

interface InfoStepProps {
  recaptchaToken: string;
  ci: string;
  di: string;
  onSubmit: (data: SignupRequest) => void;
  onBack: () => void;
  isSubmitting?: boolean;
}

/**
 * 회원가입 정보 입력 Zod 스키마
 */
const signupInfoSchema = z
  .object({
    email: z
      .string()
      .min(1, '이메일을 입력해주세요.')
      .email('올바른 이메일 형식이 아닙니다.'),
    password: z
      .string()
      .min(8, '비밀번호는 최소 8자 이상이어야 합니다.')
      .regex(
        /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])/,
        '비밀번호는 영문 대소문자, 숫자, 특수문자를 포함해야 합니다.'
      ),
    passwordConfirm: z.string().min(1, '비밀번호 확인을 입력해주세요.'),
    name: z.string().min(2, '이름은 최소 2자 이상이어야 합니다.'),
    phone: z
      .string()
      .regex(/^010-\d{4}-\d{4}$/, '휴대폰 번호 형식이 올바르지 않습니다. (예: 010-1234-5678)'),
  })
  .refine((data) => data.password === data.passwordConfirm, {
    message: '비밀번호가 일치하지 않습니다.',
    path: ['passwordConfirm'],
  });

type SignupInfoFormData = z.infer<typeof signupInfoSchema>;

/**
 * 회원가입 4단계: 정보입력
 * react-hook-form + Zod를 사용하여 유효성 검증을 수행합니다.
 *
 * FormInput 컴포넌트의 onChange 시그니처 이슈로 인해
 * 기본 Input + Label을 직접 사용합니다.
 *
 * @see docs/frontend/04_auth_security.md 2.2.1절 4단계
 *
 * @example
 * ```tsx
 * <InfoStep
 *   recaptchaToken="captcha_token"
 *   ci="ci_value"
 *   di="di_value"
 *   onSubmit={(data) => console.log('회원가입:', data)}
 *   onBack={() => console.log('이전 단계로')}
 * />
 * ```
 */
export function InfoStep({
  recaptchaToken,
  ci,
  di,
  onSubmit,
  onBack,
  isSubmitting = false,
}: InfoStepProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SignupInfoFormData>({
    resolver: zodResolver(signupInfoSchema),
    mode: 'onBlur',
  });

  const onFormSubmit = (formData: SignupInfoFormData) => {
    const { passwordConfirm, ...rest } = formData;
    const signupRequest: SignupRequest = {
      ...rest,
      recaptchaToken,
      ci,
      di,
    };
    onSubmit(signupRequest);
  };

  return (
    <form onSubmit={handleSubmit(onFormSubmit)}>
      <Card>
        <CardHeader>
          <CardTitle>정보 입력</CardTitle>
          <CardDescription>회원가입에 필요한 정보를 입력해주세요.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* 이메일 */}
          <div className="space-y-2">
            <Label htmlFor="email">
              이메일 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="email"
              type="email"
              placeholder="example@email.com"
              {...register('email')}
              aria-invalid={!!errors.email}
              aria-describedby={errors.email ? 'email-error' : undefined}
            />
            {errors.email && (
              <p id="email-error" className="text-xs text-destructive">
                {errors.email.message}
              </p>
            )}
          </div>

          {/* 비밀번호 */}
          <div className="space-y-2">
            <Label htmlFor="password">
              비밀번호 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="password"
              type="password"
              placeholder="영문 대소문자, 숫자, 특수문자 포함 8자 이상"
              {...register('password')}
              aria-invalid={!!errors.password}
              aria-describedby={errors.password ? 'password-error' : undefined}
            />
            {errors.password && (
              <p id="password-error" className="text-xs text-destructive">
                {errors.password.message}
              </p>
            )}
          </div>

          {/* 비밀번호 확인 */}
          <div className="space-y-2">
            <Label htmlFor="passwordConfirm">
              비밀번호 확인 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="passwordConfirm"
              type="password"
              placeholder="비밀번호를 다시 입력해주세요"
              {...register('passwordConfirm')}
              aria-invalid={!!errors.passwordConfirm}
              aria-describedby={errors.passwordConfirm ? 'passwordConfirm-error' : undefined}
            />
            {errors.passwordConfirm && (
              <p id="passwordConfirm-error" className="text-xs text-destructive">
                {errors.passwordConfirm.message}
              </p>
            )}
          </div>

          {/* 이름 */}
          <div className="space-y-2">
            <Label htmlFor="name">
              이름 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="name"
              type="text"
              placeholder="홍길동"
              {...register('name')}
              aria-invalid={!!errors.name}
              aria-describedby={errors.name ? 'name-error' : undefined}
            />
            {errors.name && (
              <p id="name-error" className="text-xs text-destructive">
                {errors.name.message}
              </p>
            )}
          </div>

          {/* 휴대폰 번호 */}
          <div className="space-y-2">
            <Label htmlFor="phone">
              휴대폰 번호 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="phone"
              type="tel"
              placeholder="010-1234-5678"
              {...register('phone')}
              aria-invalid={!!errors.phone}
              aria-describedby={errors.phone ? 'phone-error' : undefined}
            />
            {errors.phone && (
              <p id="phone-error" className="text-xs text-destructive">
                {errors.phone.message}
              </p>
            )}
          </div>
        </CardContent>
        <CardFooter className="gap-3">
          <Button
            type="button"
            onClick={onBack}
            variant="outline"
            className="flex-1"
            size="lg"
            disabled={isSubmitting}
          >
            이전
          </Button>
          <Button
            type="submit"
            className="flex-1"
            size="lg"
            disabled={isSubmitting}
          >
            {isSubmitting ? '처리 중...' : '회원가입'}
          </Button>
        </CardFooter>
      </Card>
    </form>
  );
}
