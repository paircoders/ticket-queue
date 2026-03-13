'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useCountdown } from '@/hooks/useCountdown';
import { useReservationStore } from '@/stores/reservation-store';

interface HoldTimerProps {
  holdExpiresAt: string;
  scheduleId: string;
}

export function HoldTimer({ holdExpiresAt, scheduleId }: HoldTimerProps) {
  const router = useRouter();
  const resetReservation = useReservationStore((state) => state.resetReservation);
  const { isExpired, isWarning, formatted } = useCountdown(holdExpiresAt);

  useEffect(() => {
    if (isExpired) {
      resetReservation();
      router.replace(`/reservation/${scheduleId}`);
    }
  }, [isExpired, resetReservation, router, scheduleId]);

  return (
    <div
      role="timer"
      aria-live={isWarning ? 'assertive' : 'polite'}
      aria-label={`선점 만료까지 남은 시간 ${formatted}`}
      className="text-center"
    >
      <p className="text-sm text-gray-500 mb-1">선점 만료까지</p>
      <p
        className={`text-3xl font-mono font-bold ${
          isWarning ? 'text-red-500 animate-pulse' : 'text-blue-600'
        }`}
      >
        {formatted}
      </p>
    </div>
  );
}
