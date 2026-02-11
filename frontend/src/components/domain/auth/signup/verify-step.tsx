'use client';

import { Button } from '@/components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/components/ui/card';
import { ShieldCheck } from 'lucide-react';
import { toast } from 'sonner';

interface VerifyStepProps {
  onNext: (ci: string, di: string) => void;
  onBack: () => void;
}

/**
 * 회원가입 3단계: 본인인증 (Stub)
 * PortOne 본인인증 연동은 백엔드 API 구현 후 진행됩니다.
 *
 * 현재는 개발용 "건너뛰기" 버튼을 제공하여 mock CI/DI 값으로 다음 단계로 이동합니다.
 *
 * @see docs/frontend/04_auth_security.md 2.2.1절 3단계, 4.2절
 *
 * TODO: usePortOne Hook 연동
 * - loadPortOneSDK() 호출
 * - window.IMP.init(impCode)
 * - window.IMP.certification() 호출
 * - 백엔드 API로 imp_uid 전송하여 CI/DI 추출
 *
 * @example
 * ```tsx
 * <VerifyStep
 *   onNext={(ci, di) => console.log('CI/DI:', ci, di)}
 *   onBack={() => console.log('이전 단계로')}
 * />
 * ```
 */
export function VerifyStep({ onNext, onBack }: VerifyStepProps) {
  const handleVerify = () => {
    toast.info('본인인증 기능은 백엔드 연동 후 사용 가능합니다.');
    // TODO: 실제 본인인증 로직
    // 1. loadPortOneSDK() 호출
    // 2. window.IMP.init(process.env.NEXT_PUBLIC_PORTONE_IMP_CODE)
    // 3. window.IMP.certification() 호출
    // 4. 응답으로 받은 imp_uid를 백엔드로 전송
    // 5. 백엔드에서 PortOne API로 CI/DI 추출
    // 6. onNext(ci, di) 호출
  };

  const handleSkip = () => {
    // 개발용 mock CI/DI 값
    const mockCi = 'mock_ci_' + Date.now();
    const mockDi = 'mock_di_' + Date.now();
    toast.success('개발용 mock CI/DI로 다음 단계로 이동합니다.');
    onNext(mockCi, mockDi);
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>본인인증</CardTitle>
        <CardDescription>1인 1계정 정책을 위해 본인인증이 필요합니다.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col items-center py-4 space-y-4">
        <ShieldCheck className="w-16 h-16 text-primary" />
        <p className="text-center text-sm text-muted-foreground">
          휴대폰 번호로 본인인증을 진행합니다.
          <br />
          본인명의 휴대폰이 필요합니다.
        </p>
      </CardContent>
      <CardFooter className="flex-col space-y-3">
        <Button onClick={handleVerify} className="w-full" size="lg">
          본인인증하기
        </Button>

        <div className="flex gap-3 w-full">
          <Button onClick={onBack} variant="outline" className="flex-1" size="lg">
            이전
          </Button>
          <Button
            onClick={handleSkip}
            variant="ghost"
            className="flex-1"
            size="lg"
          >
            건너뛰기 (개발용)
          </Button>
        </div>
      </CardFooter>
    </Card>
  );
}
