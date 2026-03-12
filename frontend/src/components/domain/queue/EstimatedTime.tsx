interface EstimatedTimeProps {
  estimatedWaitSeconds: number
}

export function EstimatedTime({ estimatedWaitSeconds }: EstimatedTimeProps) {
  const totalMinutes = Math.ceil(estimatedWaitSeconds / 60)
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60

  let timeText: string
  if (hours > 0) {
    timeText = `약 ${hours}시간 ${minutes}분`
  } else {
    timeText = `약 ${minutes}분`
  }

  return (
    <div className="text-center">
      <p className="text-sm text-gray-500 mb-1">예상 대기 시간</p>
      <p className="text-xl font-semibold text-gray-700">{timeText}</p>
    </div>
  )
}
