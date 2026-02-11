'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';

interface TermsStepProps {
  onNext: (serviceTerms: boolean, privacyTerms: boolean) => void;
  initialServiceTerms?: boolean;
  initialPrivacyTerms?: boolean;
}

/**
 * 회원가입 1단계: 약관동의
 * 서비스 이용약관과 개인정보 수집 및 이용 동의를 받습니다.
 *
 * @see docs/frontend/04_auth_security.md 2.2.1절 1단계
 *
 * @example
 * ```tsx
 * <TermsStep
 *   onNext={(service, privacy) => {
 *     console.log('약관 동의:', service, privacy);
 *   }}
 * />
 * ```
 */
export function TermsStep({
  onNext,
  initialServiceTerms = false,
  initialPrivacyTerms = false,
}: TermsStepProps) {
  const [serviceTerms, setServiceTerms] = useState(initialServiceTerms);
  const [privacyTerms, setPrivacyTerms] = useState(initialPrivacyTerms);

  const canProceed = serviceTerms && privacyTerms;

  const handleNext = () => {
    if (canProceed) {
      onNext(serviceTerms, privacyTerms);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>약관 동의</CardTitle>
        <CardDescription>서비스 이용을 위해 아래 약관에 동의해주세요.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* 서비스 이용약관 */}
        <div className="flex items-start space-x-3 border rounded-lg p-4">
          <Checkbox
            id="service-terms"
            checked={serviceTerms}
            onCheckedChange={(checked) => setServiceTerms(checked === true)}
            aria-required="true"
          />
          <div className="flex-1">
            <Label
              htmlFor="service-terms"
              className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70 cursor-pointer"
            >
              서비스 이용약관 동의 <span className="text-destructive">(필수)</span>
            </Label>
            <p className="text-xs text-muted-foreground mt-1">
              서비스 제공에 필요한 약관입니다.
            </p>
          </div>
        </div>

        {/* 개인정보 수집 및 이용 동의 */}
        <div className="flex items-start space-x-3 border rounded-lg p-4">
          <Checkbox
            id="privacy-terms"
            checked={privacyTerms}
            onCheckedChange={(checked) => setPrivacyTerms(checked === true)}
            aria-required="true"
          />
          <div className="flex-1">
            <Label
              htmlFor="privacy-terms"
              className="text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70 cursor-pointer"
            >
              개인정보 수집 및 이용 동의 <span className="text-destructive">(필수)</span>
            </Label>
            <p className="text-xs text-muted-foreground mt-1">
              회원가입 및 서비스 이용을 위한 개인정보 수집에 동의합니다.
            </p>
          </div>
        </div>
      </CardContent>
      <CardFooter className="flex-col space-y-3">
        <Button
          onClick={handleNext}
          disabled={!canProceed}
          className="w-full"
          size="lg"
        >
          다음
        </Button>

        <div className="text-center">
          <Link
            href="/login"
            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            로그인으로 돌아가기
          </Link>
        </div>
      </CardFooter>
    </Card>
  );
}
