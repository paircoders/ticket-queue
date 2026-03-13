'use client';

import { useState, useEffect, useRef } from 'react';

interface CountdownResult {
  minutes: number;
  seconds: number;
  totalSeconds: number;
  isExpired: boolean;
  isWarning: boolean; // 남은 시간 60초 미만
  formatted: string; // "MM:SS" 형식
}

/**
 * holdExpiresAt timestamp 기준으로 남은 시간을 계산하는 커스텀 훅
 * 클라이언트 시계(Date.now())를 기준으로 계산합니다.
 *
 * 주의: 클라이언트 시계가 서버와 차이가 있을 경우 타이머가 부정확할 수 있습니다.
 * TODO: 서버 응답의 타임스탬프를 이용한 offset 계산으로 개선 필요.
 *
 * @param holdExpiresAt - ISO8601 형식의 만료 시각 (예: "2026-03-13T10:05:00")
 * @returns CountdownResult
 */
export function useCountdown(holdExpiresAt: string | null): CountdownResult {
  const calculateRemaining = (): number => {
    if (!holdExpiresAt) return 0;
    const expiresAt = new Date(holdExpiresAt).getTime();
    const now = Date.now();
    return Math.max(0, Math.floor((expiresAt - now) / 1000));
  };

  const [totalSeconds, setTotalSeconds] = useState<number>(calculateRemaining);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!holdExpiresAt) {
      setTotalSeconds(0);
      return;
    }

    // 초기값 즉시 설정
    setTotalSeconds(calculateRemaining());

    intervalRef.current = setInterval(() => {
      const remaining = calculateRemaining();
      setTotalSeconds(remaining);
      if (remaining <= 0) {
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
        }
      }
    }, 1000);

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    };
  }, [holdExpiresAt]);

  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  const isExpired = totalSeconds <= 0 && holdExpiresAt !== null;
  const isWarning = totalSeconds > 0 && totalSeconds < 60;
  const formatted = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;

  return { minutes, seconds, totalSeconds, isExpired, isWarning, formatted };
}
