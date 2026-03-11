import type { SeatGrade } from '@/types/event'

interface SeatInfoProps {
  grades: SeatGrade[]
}

export function SeatInfo({ grades }: SeatInfoProps) {
  return (
    <section className="space-y-4">
      <h2 className="text-xl font-semibold text-foreground">좌석 정보</h2>
      {grades.length === 0 ? (
        <p className="text-sm text-muted-foreground">좌석 정보가 없습니다.</p>
      ) : (
        <div className="space-y-2">
          {grades.map((grade) => (
            <div
              key={grade.grade}
              className="flex items-center justify-between rounded-lg border bg-card px-4 py-3"
            >
              <span className="font-medium text-foreground">{grade.grade}</span>
              <div className="text-right">
                <p className="font-semibold text-foreground">
                  {grade.price.toLocaleString('ko-KR')}원
                </p>
                <p className="text-xs text-muted-foreground">총 {grade.seats.length}석</p>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  )
}
