'use client';

import type { SignupStep } from '@/types/signup';
import { cn } from '@/lib/utils';

interface ProgressBarProps {
  currentStep: SignupStep;
}

const STEPS = [
  { step: 1 as const, label: '약관동의' },
  { step: 2 as const, label: 'CAPTCHA' },
  { step: 3 as const, label: '본인인증' },
  { step: 4 as const, label: '정보입력' },
];

/**
 * 회원가입 위저드 진행 상태를 시각적으로 표시하는 컴포넌트
 *
 * @example
 * ```tsx
 * <ProgressBar currentStep={2} />
 * ```
 */
export function ProgressBar({ currentStep }: ProgressBarProps) {
  return (
    <nav
      className="w-full"
      role="navigation"
      aria-label="회원가입 진행 단계"
    >
      <ol className="flex items-center justify-between">
        {STEPS.map((step, index) => {
          const isCompleted = currentStep > step.step;
          const isCurrent = currentStep === step.step;
          const isFuture = currentStep < step.step;
          const isLastStep = index === STEPS.length - 1;

          return (
            <li key={step.step} className="flex items-center flex-1 last:flex-none">
              {/* Step Circle */}
              <div className="flex flex-col items-center">
                <div
                  className={cn(
                    'w-10 h-10 rounded-full flex items-center justify-center text-sm font-semibold transition-colors',
                    {
                      'bg-primary text-primary-foreground': isCompleted || isCurrent,
                      'border-2 border-muted-foreground text-muted-foreground': isFuture,
                    }
                  )}
                  aria-current={isCurrent ? 'step' : undefined}
                  aria-label={`${step.label} (${isCompleted ? '완료' : isCurrent ? '현재' : '대기'})`}
                >
                  {step.step}
                </div>
                {/* Step Label (hidden on mobile) */}
                <span
                  className={cn(
                    'hidden md:block mt-2 text-xs font-medium transition-colors',
                    {
                      'text-primary': isCompleted || isCurrent,
                      'text-muted-foreground': isFuture,
                    }
                  )}
                >
                  {step.label}
                </span>
              </div>

              {/* Connection Line */}
              {!isLastStep && (
                <div
                  className={cn(
                    'flex-1 h-0.5 mx-2 transition-colors',
                    {
                      'bg-primary': isCompleted,
                      'bg-muted': !isCompleted,
                    }
                  )}
                  aria-hidden="true"
                />
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
