import Link from 'next/link'

export default function ReservationNotFound() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[50vh] gap-4">
      <h2 className="text-xl font-semibold">존재하지 않는 공연 회차입니다</h2>
      <p className="text-muted-foreground">요청하신 공연 회차를 찾을 수 없습니다.</p>
      <Link
        href="/events"
        className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
      >
        공연 목록으로 돌아가기
      </Link>
    </div>
  )
}
