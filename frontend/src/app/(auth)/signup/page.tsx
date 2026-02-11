'use client';

import { useState } from 'react';
import { toast } from 'sonner';
import type { SignupStep, SignupWizardData } from '@/types/signup';
import type { SignupRequest } from '@/types/auth';
import {
  ProgressBar,
  TermsStep,
  CaptchaStep,
  VerifyStep,
  InfoStep,
} from '@/components/domain/auth/signup';

/**
 * 회원가입 페이지 (4단계 위저드)
 *
 * Step 1: 약관동의 (TermsStep)
 * Step 2: CAPTCHA (CaptchaStep)
 * Step 3: 본인인증 (VerifyStep - stub)
 * Step 4: 정보입력 (InfoStep)
 *
 * @see docs/frontend/04_auth_security.md 2.2.1절
 */
export default function SignupPage() {
  const [currentStep, setCurrentStep] = useState<SignupStep>(1);
  const [wizardData, setWizardData] = useState<SignupWizardData>({
    serviceTermsAgreed: false,
    privacyTermsAgreed: false,
    recaptchaToken: null,
    ci: null,
    di: null,
  });

  // Step 1 → 2: 약관 동의
  const handleTermsNext = (serviceTerms: boolean, privacyTerms: boolean) => {
    setWizardData((prev) => ({
      ...prev,
      serviceTermsAgreed: serviceTerms,
      privacyTermsAgreed: privacyTerms,
    }));
    setCurrentStep(2);
  };

  // Step 2 → 3: CAPTCHA 인증
  const handleCaptchaNext = (token: string) => {
    setWizardData((prev) => ({
      ...prev,
      recaptchaToken: token,
    }));
    setCurrentStep(3);
  };

  // Step 3 → 4: 본인인증
  const handleVerifyNext = (ci: string, di: string) => {
    setWizardData((prev) => ({
      ...prev,
      ci,
      di,
    }));
    setCurrentStep(4);
  };

  // Step 4: 회원가입 제출
  const handleSignupSubmit = async (signupRequest: SignupRequest) => {
    // TODO: 백엔드 연동 - POST /auth/signup API 호출
    console.log('회원가입 요청 데이터:', signupRequest);
    toast.success('회원가입 요청이 전송되었습니다. (백엔드 연동 전 개발용 메시지)');

    // TODO: 성공 시 로그인 페이지로 리디렉션
    // router.push('/login');
  };

  // 이전 단계로 이동 (데이터 보존)
  const handleBack = () => {
    setCurrentStep((prev) => Math.max(1, prev - 1) as SignupStep);
  };

  return (
    <div className="space-y-6">
      {/* Progress Bar */}
      <ProgressBar currentStep={currentStep} />

      {/* Step 1: 약관동의 */}
      {currentStep === 1 && (
        <TermsStep
          onNext={handleTermsNext}
          initialServiceTerms={wizardData.serviceTermsAgreed}
          initialPrivacyTerms={wizardData.privacyTermsAgreed}
        />
      )}

      {/* Step 2: CAPTCHA */}
      {currentStep === 2 && (
        <CaptchaStep onNext={handleCaptchaNext} onBack={handleBack} />
      )}

      {/* Step 3: 본인인증 */}
      {currentStep === 3 && (
        <VerifyStep onNext={handleVerifyNext} onBack={handleBack} />
      )}

      {/* Step 4: 정보입력 */}
      {currentStep === 4 && wizardData.recaptchaToken && wizardData.ci && wizardData.di && (
        <InfoStep
          recaptchaToken={wizardData.recaptchaToken}
          ci={wizardData.ci}
          di={wizardData.di}
          onSubmit={handleSignupSubmit}
          onBack={handleBack}
        />
      )}
    </div>
  );
}
