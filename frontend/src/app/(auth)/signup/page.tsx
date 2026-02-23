'use client'

import { useState } from 'react'
import Link from 'next/link'
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
} from '@/components/ui/card'
import {
  StepIndicator,
  TermsStep,
  CaptchaStep,
  VerifyStep,
  InfoStep,
} from '@/components/domain/signup'

const STEPS = ['약관 동의', 'CAPTCHA', '본인인증', '정보 입력']

export default function SignupPage() {
  const [currentStep, setCurrentStep] = useState(0)
  const [recaptchaToken, setRecaptchaToken] = useState('')
  const [identityVerificationId, setIdentityVerificationId] = useState('')

  function handleTermsNext() {
    setCurrentStep(1)
  }

  function handleCaptchaNext(token: string) {
    setRecaptchaToken(token)
    setCurrentStep(2)
  }

  function handleVerifyNext(id: string) {
    setIdentityVerificationId(id)
    setCurrentStep(3)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-xl">회원가입</CardTitle>
        <CardDescription>Ticket Queue 계정을 만들어보세요.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <StepIndicator currentStep={currentStep} steps={STEPS} />

        <div className="pt-2">
          {currentStep === 0 && <TermsStep onNext={handleTermsNext} />}

          {currentStep === 1 && (
            <CaptchaStep
              onNext={handleCaptchaNext}
              onBack={() => setCurrentStep(0)}
            />
          )}

          {currentStep === 2 && (
            <VerifyStep
              onNext={handleVerifyNext}
              onBack={() => setCurrentStep(1)}
            />
          )}

          {currentStep === 3 && (
            <InfoStep
              recaptchaToken={recaptchaToken}
              identityVerificationId={identityVerificationId}
              onBack={() => setCurrentStep(2)}
              onGoToCaptcha={() => setCurrentStep(1)}
            />
          )}
        </div>

        <div className="text-center text-sm text-muted-foreground">
          이미 계정이 있으신가요?{' '}
          <Link
            href="/login"
            className="text-primary font-medium hover:underline"
          >
            로그인
          </Link>
        </div>
      </CardContent>
    </Card>
  )
}
