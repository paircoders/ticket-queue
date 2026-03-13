'use client';

import { useEffect, useRef, useState } from 'react';
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
  const hasRedirectedRef = useRef(false);
  const warningTriggeredRef = useRef(false);
  const [showWarningAnnouncement, setShowWarningAnnouncement] = useState(false);

  useEffect(() => {
    if (isExpired && !hasRedirectedRef.current) {
      hasRedirectedRef.current = true;
      resetReservation();
      router.replace(`/reservation/${scheduleId}`);
    }
  }, [isExpired, resetReservation, router, scheduleId]);

  // isWarning이 false → true로 전환될 때 단 한 번만 assertive 알림 발생
  useEffect(() => {
    if (isWarning && !warningTriggeredRef.current) {
      warningTriggeredRef.current = true;
      setShowWarningAnnouncement(true);
    }
  }, [isWarning]);

  return (
    <div className="text-center">
      {/* 경고 임계값 도달 시 단 한 번만 발생하는 assertive 알림 */}
      {showWarningAnnouncement && (
        <span
          role="status"
          aria-live="assertive"
          aria-atomic="true"
          className="sr-only"
        >
          선점 만료까지 1분 미만 남았습니다.
        </span>
      )}
      {/* 매초 업데이트되는 타이머는 polite로 유지 (스크린리더 방해 방지) */}
      <div
        role="timer"
        aria-live="polite"
        aria-label={`선점 만료까지 남은 시간 ${formatted}`}
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
    </div>
  );
}
