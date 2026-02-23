'use client'

import { useState } from 'react'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'

interface TermsStepProps {
  onNext: () => void
}

export function TermsStep({ onNext }: TermsStepProps) {
  const [termsAgreed, setTermsAgreed] = useState(false)
  const [privacyAgreed, setPrivacyAgreed] = useState(false)

  const allAgreed = termsAgreed && privacyAgreed

  function handleAllAgreedChange(checked: boolean) {
    setTermsAgreed(checked)
    setPrivacyAgreed(checked)
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-foreground">약관 동의</h2>
        <p className="text-sm text-muted-foreground mt-1">
          서비스 이용을 위해 아래 약관에 동의해주세요.
        </p>
      </div>

      <div className="space-y-4">
        <div className="flex items-center space-x-2 pb-3 border-b">
          <Checkbox
            id="all-agree"
            checked={allAgreed}
            onCheckedChange={(checked) =>
              handleAllAgreedChange(checked === true)
            }
          />
          <Label
            htmlFor="all-agree"
            className="text-sm font-semibold cursor-pointer"
          >
            전체 동의
          </Label>
        </div>

        <div className="flex items-center space-x-2">
          <Checkbox
            id="terms"
            checked={termsAgreed}
            onCheckedChange={(checked) => setTermsAgreed(checked === true)}
          />
          <Label htmlFor="terms" className="text-sm cursor-pointer">
            <span className="text-destructive font-medium">[필수]</span> 서비스
            이용약관에 동의합니다.
          </Label>
        </div>

        <div className="flex items-center space-x-2">
          <Checkbox
            id="privacy"
            checked={privacyAgreed}
            onCheckedChange={(checked) => setPrivacyAgreed(checked === true)}
          />
          <Label htmlFor="privacy" className="text-sm cursor-pointer">
            <span className="text-destructive font-medium">[필수]</span>{' '}
            개인정보 수집 및 이용에 동의합니다.
          </Label>
        </div>
      </div>

      <Button
        className="w-full"
        disabled={!allAgreed}
        onClick={onNext}
      >
        다음
      </Button>
    </div>
  )
}
