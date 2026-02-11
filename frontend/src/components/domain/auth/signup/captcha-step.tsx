'use client';

import { useState, useRef } from 'react';
import ReCAPTCHA from 'react-google-recaptcha';
import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { toast } from 'sonner';

interface CaptchaStepProps {
  onNext: (token: string) => void;
  onBack: () => void;
}

/**
 * 회원가입 2단계: CAPTCHA 인증
 * Google reCAPTCHA를 통해 봇이 아님을 검증합니다.
 *
 * @see docs/frontend/04_auth_security.md 2.2.1절 2단계, 3.1절
 *
 * @example
 * ```tsx
 * <CaptchaStep
 *   onNext={(token) => console.log('CAPTCHA 토큰:', token)}
 *   onBack={() => console.log('이전 단계로')}
 * />
 * ```
 */
export function CaptchaStep({ onNext, onBack }: CaptchaStepProps) {
  const [captchaToken, setCaptchaToken] = useState<string | null>(null);
  const recaptchaRef = useRef<ReCAPTCHA>(null);

  // 환경변수에서 reCAPTCHA Site Key 가져오기
  const siteKey = process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY;

  if (!siteKey) {
    console.error('NEXT_PUBLIC_RECAPTCHA_SITE_KEY 환경변수가 설정되지 않았습니다.');
    return (
      <Card>
        <CardHeader>
          <CardTitle>보안 인증</CardTitle>
          <CardDescription>봇이 아님을 확인하기 위한 인증 절차입니다.</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="text-center text-destructive">
            <p className="text-sm">reCAPTCHA 설정 오류</p>
            <p className="text-xs mt-2">환경변수를 확인해주세요.</p>
          </div>
        </CardContent>
        <CardFooter>
          <Button onClick={onBack} variant="outline" className="w-full">
            이전
          </Button>
        </CardFooter>
      </Card>
    );
  }

  const handleCaptchaChange = (token: string | null) => {
    setCaptchaToken(token);
  };

  const handleCaptchaExpired = () => {
    setCaptchaToken(null);
    toast.warning('CAPTCHA가 만료되었습니다. 다시 시도해주세요.');
  };

  const handleCaptchaError = () => {
    setCaptchaToken(null);
    toast.error('CAPTCHA 로드 중 오류가 발생했습니다.');
  };

  const handleNext = () => {
    if (!captchaToken) {
      toast.error('CAPTCHA 인증을 완료해주세요.');
      return;
    }
    onNext(captchaToken);
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>보안 인증</CardTitle>
        <CardDescription>봇이 아님을 확인하기 위한 인증 절차입니다.</CardDescription>
      </CardHeader>
      <CardContent className="flex justify-center">
        <ReCAPTCHA
          ref={recaptchaRef}
          sitekey={siteKey}
          onChange={handleCaptchaChange}
          onExpired={handleCaptchaExpired}
          onErrored={handleCaptchaError}
        />
      </CardContent>
      <CardFooter className="gap-3">
        <Button onClick={onBack} variant="outline" className="flex-1" size="lg">
          이전
        </Button>
        <Button
          onClick={handleNext}
          disabled={!captchaToken}
          className="flex-1"
          size="lg"
        >
          다음
        </Button>
      </CardFooter>
    </Card>
  );
}
