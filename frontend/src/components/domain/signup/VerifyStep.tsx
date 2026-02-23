'use client'

import { useState } from 'react'
import { toast } from 'sonner'
import { ShieldCheckIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { requestIdentityVerification } from '@/lib/portone/identity-verification'

interface VerifyStepProps {
  onNext: (identityVerificationId: string) => void
  onBack: () => void
}

export function VerifyStep({ onNext, onBack }: VerifyStepProps) {
  const [isPending, setIsPending] = useState(false)
  const [verified, setVerified] = useState(false)

  async function handleVerify() {
    setIsPending(true)
    try {
      const identityVerificationId = await requestIdentityVerification()
      setVerified(true)
      onNext(identityVerificationId)
    } catch (err) {
      const message =
        err instanceof Error ? err.message : '본인인증에 실패했습니다.'
      toast.error(message)
    } finally {
      setIsPending(false)
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-foreground">본인인증</h2>
        <p className="text-sm text-muted-foreground mt-1">
          안전한 서비스 이용을 위해 본인인증이 필요합니다.
        </p>
      </div>

      <div className="flex flex-col items-center gap-4 py-6 rounded-lg border bg-muted/30">
        <ShieldCheckIcon
          className={`w-12 h-12 ${verified ? 'text-primary' : 'text-muted-foreground'}`}
        />
        <p className="text-sm text-muted-foreground text-center">
          {verified
            ? '본인인증이 완료되었습니다.'
            : '아래 버튼을 클릭하여 본인인증을 진행해주세요.'}
        </p>
        {!verified && (
          <Button
            variant="outline"
            onClick={handleVerify}
            loading={isPending}
            disabled={isPending}
          >
            본인인증하기
          </Button>
        )}
      </div>

      <Button variant="outline" className="w-full" onClick={onBack}>
        이전
      </Button>
    </div>
  )
}
