interface SeatLegendProps {
  gradeInfo?: Array<{ grade: string; price: number }>
}

const STATUS_ITEMS = [
  { color: 'bg-green-500', label: '선택 가능' },
  { color: 'bg-gray-400', label: '선점 중' },
  { color: 'bg-red-500', label: '판매 완료' },
  { color: 'bg-blue-500', label: '선택됨' },
] as const

export function SeatLegend({ gradeInfo }: SeatLegendProps) {
  return (
    <div className="space-y-3 rounded-lg border bg-card px-4 py-3">
      <div className="grid grid-cols-2 gap-x-6 gap-y-2 sm:flex sm:flex-row sm:flex-wrap sm:gap-x-6 sm:gap-y-0">
        {STATUS_ITEMS.map(({ color, label }) => (
          <div key={label} className="flex items-center gap-2">
            <span
              className={`h-4 w-4 shrink-0 rounded-sm ${color}`}
              aria-hidden="true"
            />
            <span className="text-sm text-foreground">{label}</span>
          </div>
        ))}
      </div>

      {gradeInfo && gradeInfo.length > 0 && (
        <>
          <div className="border-t" />
          <div className="grid grid-cols-2 gap-x-6 gap-y-2 sm:flex sm:flex-row sm:flex-wrap sm:gap-x-6 sm:gap-y-0">
            {gradeInfo.map(({ grade, price }) => (
              <div key={grade} className="flex items-center gap-2">
                <span className="text-sm font-medium text-foreground">{grade}</span>
                <span className="text-sm text-muted-foreground">
                  {price.toLocaleString('ko-KR')}원
                </span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}
