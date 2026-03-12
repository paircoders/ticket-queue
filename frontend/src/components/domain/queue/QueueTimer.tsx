'use client'

import { useEffect, useRef, useState } from 'react'

interface QueueTimerProps {
  enteredAt: number
  onExpire?: () => void
}

const TIMEOUT_MS = 10 * 60 * 1000 // 10분

export function QueueTimer({ enteredAt, onExpire }: QueueTimerProps) {
  const [remainingMs, setRemainingMs] = useState(() => {
    return Math.max(0, TIMEOUT_MS - (Date.now() - enteredAt))
  })
  const expiredRef = useRef(false)

  useEffect(() => {
    const interval = setInterval(() => {
      const next = Math.max(0, TIMEOUT_MS - (Date.now() - enteredAt))
      setRemainingMs(next)
      if (next === 0 && !expiredRef.current) {
        expiredRef.current = true
        onExpire?.()
      }
    }, 1000)
    return () => clearInterval(interval)
  }, [enteredAt, onExpire])

  const totalSeconds = Math.floor(remainingMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  const formatted = `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`

  let colorClass: string
  if (minutes >= 3) {
    colorClass = 'text-blue-600'
  } else if (minutes >= 1) {
    colorClass = 'text-yellow-600'
  } else {
    colorClass = 'text-red-600 animate-pulse'
  }

  return (
    <div className="text-center">
      <p className="text-sm text-gray-500 mb-1">남은 시간</p>
      <p className={`text-3xl font-mono font-bold ${colorClass}`}>{formatted}</p>
    </div>
  )
}
