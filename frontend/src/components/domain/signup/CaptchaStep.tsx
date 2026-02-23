'use client'

import { useRef } from 'react'
import ReCAPTCHA from 'react-google-recaptcha'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'

interface CaptchaStepProps {
  onNext: (token: string) => void
  onBack: () => void
}

export function CaptchaStep({ onNext, onBack }: CaptchaStepProps) {
  const recaptchaRef = useRef<ReCAPTCHA>(null)
  const siteKey = process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY

  if (!siteKey) {
    throw new Error('reCAPTCHA Site Key가 설정되지 않았습니다.')
  }

  function handleChange(token: string | null) {
    if (token) {
      onNext(token)
    }
  }

  function handleExpired() {
    toast.warning('CAPTCHA가 만료되었습니다. 다시 시도해주세요.')
    recaptchaRef.current?.reset()
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-foreground">보안 확인</h2>
        <p className="text-sm text-muted-foreground mt-1">
          로봇이 아님을 확인해주세요.
        </p>
      </div>

      <div className="flex justify-center">
        <ReCAPTCHA
          ref={recaptchaRef}
          sitekey={siteKey}
          onChange={handleChange}
          onExpired={handleExpired}
        />
      </div>

      <Button variant="outline" className="w-full" onClick={onBack}>
        이전
      </Button>
    </div>
  )
}
