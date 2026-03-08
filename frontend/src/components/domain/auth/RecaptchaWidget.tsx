'use client'

import { forwardRef, useImperativeHandle, useRef } from 'react'
import ReCAPTCHA from 'react-google-recaptcha'
import { toast } from 'sonner'

interface RecaptchaWidgetProps {
  onChange: (token: string | null) => void
}

export interface RecaptchaWidgetHandle {
  reset: () => void
}

export const RecaptchaWidget = forwardRef<RecaptchaWidgetHandle, RecaptchaWidgetProps>(
  function RecaptchaWidget({ onChange }, ref) {
    const recaptchaRef = useRef<ReCAPTCHA>(null)
    const siteKey = process.env.NEXT_PUBLIC_RECAPTCHA_SITE_KEY

    if (!siteKey) {
      throw new Error('reCAPTCHA Site Key가 설정되지 않았습니다.')
    }

    useImperativeHandle(ref, () => ({
      reset() {
        recaptchaRef.current?.reset()
      },
    }))

    function handleExpired() {
      toast.warning('CAPTCHA가 만료되었습니다. 다시 시도해주세요.')
      onChange(null)
    }

    // 개선 #3: 네트워크 오류 등 reCAPTCHA 자체 에러 처리
    function handleError() {
      toast.error('보안 확인에 실패했습니다. 페이지를 새로고침해주세요.')
      onChange(null)
    }

    return (
      <div data-testid="recaptcha-container">
        <ReCAPTCHA
          ref={recaptchaRef}
          sitekey={siteKey}
          onChange={onChange}
          onExpired={handleExpired}
          onErrored={handleError}
        />
      </div>
    )
  }
)
