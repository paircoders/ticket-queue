import Link from 'next/link'

export function HeroBanner() {
  return (
    <section className="bg-gradient-to-br from-primary-600 to-primary-800 text-white">
      <div className="max-w-7xl mx-auto px-6 py-16 md:py-24 text-center">
        <h1 className="text-h1 font-bold mb-4">공정한 티켓팅의 시작</h1>
        <p className="text-body text-primary-100 mb-8 max-w-xl mx-auto">
          대기열 시스템으로 누구나 공정하게 좋아하는 공연을 만나보세요
        </p>
        <Link
          href="/events"
          className="inline-block bg-white text-primary-700 font-semibold px-8 py-3 rounded-lg hover:bg-primary-50 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-primary-700"
        >
          공연 둘러보기
        </Link>
      </div>
    </section>
  )
}
