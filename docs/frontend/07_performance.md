# ⚡ 성능/SEO 전략

## 1. 페이지별 렌더링 전략

### 1.1 렌더링 전략 분류

> **CSR-First 철학**: 이 프로젝트는 대규모 트래픽 처리를 위한 백엔드 시스템(대기열, 분산 락, SAGA 패턴) 검증이 핵심입니다. 따라서 프론트엔드는 CSR을 기본으로 하여 (1) 백엔드 부하 최소화 - SSR은 서버 측에서 매 요청마다 API 호출을 유발하므로 불필요한 경우 제거, (2) 아키텍처 투명성 유지 - 브라우저 → API Gateway → 백엔드 경로를 명확하게 유지하여 프로젝트의 진짜 가치(백엔드 로직)에 집중할 수 있도록 설계합니다.

| 페이지 | 전략 | 이유 | Data Fetching |
|--------|------|------|--------------|
| `/` | **SSR** | SEO, 초기 로딩 속도 | Server Component |
| `/events` | **SSR** | SEO, 공연 목록 크롤링 | Server Component |
| `/events/[id]` | **SSR** | SEO, OG 태그 최적화 | Server Component |
| `/login` | **CSR** | 인증 플로우, SEO 불필요 | Client Component |
| `/signup` | **CSR** | 인증 플로우, SEO 불필요 | Client Component |
| `/queue/[scheduleId]` | **CSR** | 실시간 폴링, SEO 불필요 | Client Component |
| `/reservation/[scheduleId]` | **CSR** | 실시간 좌석 상태, SEO 불필요 | Client Component |
| `/payment/[reservationId]` | **CSR** | 결제 위젯, SEO 불필요 | Client Component |
| `/mypage` | **CSR** | 사용자 데이터, SEO 불필요 | Client Component |

### 1.2 SSR 예시 (공연 목록)

```typescript
// app/(main)/events/page.tsx
import { getEvents } from '@/lib/api/events'
import EventList from '@/components/domain/event/EventList'

export const metadata = {
  title: '공연 목록 | Ticket Queue',
  description: '최신 공연 정보를 확인하고 티켓을 예매하세요.',
}

export default async function EventsPage({
  searchParams,
}: {
  searchParams: { page?: string; keyword?: string }
}) {
  const events = await getEvents({
    page: Number(searchParams.page) || 1,
    size: 20,
    keyword: searchParams.keyword,
  })

  return (
    <div>
      <h1>공연 목록</h1>
      <EventList events={events} />
    </div>
  )
}
```

### 1.3 SSR + OG 태그 (공연 상세)

```typescript
// app/(main)/events/[id]/page.tsx
import { getEventDetail } from '@/lib/api/events'
import EventDetail from '@/components/domain/event/EventDetail'
import type { Metadata } from 'next'

export async function generateMetadata({
  params,
}: {
  params: { id: string }
}): Promise<Metadata> {
  const event = await getEventDetail(params.id)

  return {
    title: `${event.title} | Ticket Queue`,
    description: event.description,
    openGraph: {
      title: event.title,
      description: event.description,
      images: [
        {
          url: event.image,
          width: 1200,
          height: 630,
          alt: event.title,
        },
      ],
      type: 'website',
      locale: 'ko_KR',
    },
    twitter: {
      card: 'summary_large_image',
      title: event.title,
      description: event.description,
      images: [event.image],
    },
  }
}

export default async function EventDetailPage({
  params,
}: {
  params: { id: string }
}) {
  const event = await getEventDetail(params.id)

  return <EventDetail event={event} />
}
```

---

## 2. 이미지 최적화 (next/image)

### 2.1 Next.js Image 컴포넌트 사용

```typescript
// components/domain/event/EventCard.tsx
import Image from 'next/image'

export default function EventCard({ event }: { event: Event }) {
  return (
    <div className="event-card">
      <Image
        src={event.image}
        alt={event.title}
        width={400}
        height={300}
        priority={false} // 중요한 이미지는 true
        placeholder="blur" // 블러 플레이스홀더
        blurDataURL="data:image/svg+xml;base64,..." // 블러 이미지
        sizes="(max-width: 768px) 100vw, (max-width: 1200px) 50vw, 33vw"
      />
      <h3>{event.title}</h3>
    </div>
  )
}
```

### 2.2 이미지 최적화 설정

```javascript
// next.config.mjs
const nextConfig = {
  images: {
    domains: ['cdn.ticket-queue.com'], // 외부 이미지 도메인
    formats: ['image/avif', 'image/webp'], // 최신 포맷 지원
    deviceSizes: [640, 750, 828, 1080, 1200, 1920, 2048, 3840], // 디바이스 크기
    imageSizes: [16, 32, 48, 64, 96, 128, 256, 384], // 작은 이미지 크기
    minimumCacheTTL: 60 * 60 * 24 * 365, // 1년 캐싱
  },
}

export default nextConfig
```

### 2.3 이미지 최적화 체크리스트

- [ ] `next/image` 사용 (자동 포맷 변환, Lazy Loading)
- [ ] `priority` 속성으로 LCP 이미지 우선 로딩
- [ ] `placeholder="blur"` 로 로딩 UX 개선
- [ ] `sizes` 속성으로 반응형 이미지 최적화
- [ ] WebP/AVIF 포맷 지원

---

## 3. 번들 사이즈 최적화

### 3.1 Dynamic Import (코드 스플리팅)

```typescript
// app/(main)/events/[id]/page.tsx
import dynamic from 'next/dynamic'

// PortOne SDK는 결제 페이지에서만 필요하므로 동적 임포트
const PaymentWidget = dynamic(() => import('@/components/domain/payment/PaymentWidget'), {
  loading: () => <Spinner />,
  ssr: false, // 클라이언트 전용
})

// 차트 라이브러리는 관리자 페이지에서만 필요
const RevenueChart = dynamic(() => import('@/components/domain/admin/RevenueChart'), {
  loading: () => <ChartSkeleton />,
})
```

### 3.2 Tree Shaking

```typescript
// ❌ 나쁜 예: 전체 라이브러리 임포트
import _ from 'lodash'

// ✅ 좋은 예: 필요한 함수만 임포트
import { debounce, throttle } from 'lodash-es'
```

### 3.3 Bundle Analyzer 설정

```javascript
// next.config.mjs
import { withBundleAnalyzer } from '@next/bundle-analyzer'

const nextConfig = {
  // ... 기존 설정
}

export default withBundleAnalyzer({
  enabled: process.env.ANALYZE === 'true',
})(nextConfig)
```

**실행**:
```bash
ANALYZE=true pnpm build
```

### 3.4 번들 사이즈 목표

| 항목 | 목표 | 현재 | 상태 |
|------|------|------|------|
| **First Load JS** | < 200KB | - | - |
| **Route JS (공통)** | < 50KB | - | - |
| **Route JS (페이지별)** | < 100KB | - | - |

---

## 4. Core Web Vitals 목표

### 4.1 측정 지표 및 목표

| 지표 | 목표 | 설명 | 개선 방법 |
|------|------|------|----------|
| **LCP** (Largest Contentful Paint) | < 2.5s | 가장 큰 콘텐츠 렌더링 시간 | 이미지 최적화, SSR, CDN |
| **FID** (First Input Delay) | < 100ms | 첫 번째 입력 지연 시간 | 코드 스플리팅, Hydration 최적화 |
| **CLS** (Cumulative Layout Shift) | < 0.1 | 레이아웃 이동 누적 | 이미지 크기 지정, 폰트 최적화 |
| **INP** (Interaction to Next Paint) | < 200ms | 인터랙션 응답 시간 | 이벤트 핸들러 최적화 |
| **TTFB** (Time to First Byte) | < 600ms | 첫 바이트까지의 시간 | 서버 응답 최적화 |

### 4.2 LCP 최적화

```typescript
// app/(main)/events/page.tsx
import Image from 'next/image'

export default function EventsPage() {
  return (
    <div>
      {/* LCP 이미지는 priority 설정 */}
      <Image
        src="/hero-banner.jpg"
        alt="Hero Banner"
        width={1920}
        height={600}
        priority // LCP 이미지
        fetchPriority="high"
      />

      {/* 나머지 콘텐츠 */}
    </div>
  )
}
```

### 4.3 CLS 최적화

```typescript
// components/domain/event/EventCard.tsx
export default function EventCard({ event }: { event: Event }) {
  return (
    <div className="event-card">
      {/* 이미지 크기 명시 (CLS 방지) */}
      <Image
        src={event.image}
        alt={event.title}
        width={400}
        height={300}
        style={{ aspectRatio: '4/3' }} // 비율 고정
      />

      {/* 폰트 로딩 중에도 공간 확보 */}
      <h3 className="font-bold text-xl" style={{ minHeight: '2rem' }}>
        {event.title}
      </h3>
    </div>
  )
}
```

### 4.4 Vercel Analytics로 측정

```typescript
// app/layout.tsx
import { Analytics } from '@vercel/analytics/react'
import { SpeedInsights } from '@vercel/speed-insights/next'

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body>
        {children}
        <Analytics /> {/* Vercel Analytics */}
        <SpeedInsights /> {/* Speed Insights */}
      </body>
    </html>
  )
}
```

---

## 5. SEO 메타 태그 전략

### 5.1 공통 메타 태그 (Root Layout)

```typescript
// app/layout.tsx
import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: {
    default: 'Ticket Queue | 공정한 티켓팅 플랫폼',
    template: '%s | Ticket Queue',
  },
  description: '대기열 시스템으로 공정한 티켓 예매를 경험하세요.',
  keywords: ['티켓팅', '공연', '콘서트', '예매', '대기열'],
  authors: [{ name: 'Ticket Queue Team' }],
  creator: 'Ticket Queue',
  publisher: 'Ticket Queue',
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      'max-image-preview': 'large',
      'max-snippet': -1,
    },
  },
  openGraph: {
    type: 'website',
    locale: 'ko_KR',
    url: 'https://ticket-queue.com',
    siteName: 'Ticket Queue',
  },
  twitter: {
    card: 'summary_large_image',
    site: '@ticketqueue',
  },
  verification: {
    google: 'google-site-verification-code',
    other: {
      naver: 'naver-site-verification-code',
    },
  },
}
```

### 5.2 페이지별 동적 메타 태그

**공연 상세 페이지**:
```typescript
// app/(main)/events/[id]/page.tsx
export async function generateMetadata({
  params,
}: {
  params: { id: string }
}): Promise<Metadata> {
  const event = await getEventDetail(params.id)

  return {
    title: event.title,
    description: event.description,
    openGraph: {
      title: event.title,
      description: event.description,
      images: [{ url: event.image }],
      type: 'website',
    },
    alternates: {
      canonical: `https://ticket-queue.com/events/${params.id}`,
    },
  }
}
```

### 5.3 Structured Data (JSON-LD)

```typescript
// components/domain/event/EventStructuredData.tsx
export default function EventStructuredData({ event }: { event: Event }) {
  const structuredData = {
    '@context': 'https://schema.org',
    '@type': 'Event',
    name: event.title,
    description: event.description,
    image: event.image,
    startDate: event.startDate,
    endDate: event.endDate,
    location: {
      '@type': 'Place',
      name: event.venue.name,
      address: {
        '@type': 'PostalAddress',
        streetAddress: event.venue.address,
        addressLocality: event.venue.city,
        addressCountry: 'KR',
      },
    },
    offers: {
      '@type': 'Offer',
      url: `https://ticket-queue.com/events/${event.id}`,
      price: event.minPrice,
      priceCurrency: 'KRW',
      availability: 'https://schema.org/InStock',
    },
  }

  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: JSON.stringify(structuredData) }}
    />
  )
}
```

---

## 6. Vercel 배포 최적화

### 6.1 Edge Functions (미들웨어)

```typescript
// middleware.ts
export const config = {
  matcher: [
    '/((?!api|_next/static|_next/image|favicon.ico).*)',
  ],
}

// Edge Runtime에서 실행 (초고속)
export default function middleware(request: NextRequest) {
  // 인증 체크 로직 (Edge에서 실행)
  // ...
}
```

### 6.2 Incremental Static Regeneration (ISR)

```typescript
// app/(main)/events/page.tsx
export const revalidate = 60 // 60초마다 재검증

export default async function EventsPage() {
  const events = await getEvents()

  return <EventList events={events} />
}
```

### 6.3 Vercel 환경 변수 설정

**Vercel Dashboard > Settings > Environment Variables**:
- `NEXT_PUBLIC_API_BASE_URL`: API Gateway URL
- `NEXT_PUBLIC_RECAPTCHA_SITE_KEY`: reCAPTCHA Site Key
- `NEXT_PUBLIC_PORTONE_IMP_CODE`: PortOne Imp Code
- `JWT_SECRET`: JWT Secret Key (서버 전용)

### 6.4 Vercel 배포 설정

```json
// vercel.json
{
  "buildCommand": "pnpm build",
  "devCommand": "pnpm dev",
  "installCommand": "pnpm install",
  "framework": "nextjs",
  "regions": ["icn1"],
  "headers": [
    {
      "source": "/(.*)",
      "headers": [
        {
          "key": "X-Content-Type-Options",
          "value": "nosniff"
        },
        {
          "key": "X-Frame-Options",
          "value": "DENY"
        },
        {
          "key": "X-XSS-Protection",
          "value": "1; mode=block"
        }
      ]
    }
  ]
}
```

### 6.5 GitHub 연동 자동 CI/CD

Vercel과 GitHub 리포지토리를 연동하면 코드 변경 시 자동으로 빌드 및 배포가 수행됩니다.

**트리거별 동작**:

| Git 이벤트 | 배포 환경 | 동작 | 배포 URL 예시 |
|-----------|----------|------|-------------|
| **`main` 브랜치 push** | Production | 자동 프로덕션 배포 | `https://ticket-queue.vercel.app` |
| **`develop` 브랜치 push** | Staging (선택) | 자동 스테이징 배포 | `https://ticket-queue-staging.vercel.app` |
| **Pull Request 생성** | Preview | PR별 프리뷰 URL 자동 생성 | `https://ticket-queue-pr-123.vercel.app` |
| **PR에 새 커밋 push** | Preview | 프리뷰 URL 자동 업데이트 | 동일 URL 재배포 |
| **PR 병합 후 닫기** | - | 프리뷰 환경 자동 삭제 | - |

**설정 방법**:

1. **Vercel Dashboard → Project Settings → Git**
   - Production Branch: `main` 설정
   - Preview Branches: `All branches` 또는 `develop` 지정

2. **Environment Variables 분리**
   - Production: 운영 환경 변수 (`NEXT_PUBLIC_API_BASE_URL=https://api.ticket-queue.com`)
   - Preview: 테스트 환경 변수 (`NEXT_PUBLIC_API_BASE_URL=https://api-dev.ticket-queue.com`)

3. **PR 프리뷰 활용**
   - PR 생성 시 Vercel 봇이 자동으로 프리뷰 URL 코멘트 추가
   - 코드 리뷰 시 실제 화면에서 동작 확인 가능
   - UI/UX 변경사항을 팀원과 쉽게 공유

4. **배포 알림**
   - Vercel Integration으로 Slack, Discord 등에 배포 알림 설정 가능
   - GitHub Checks API를 통해 PR 상태에 빌드 성공/실패 표시

5. **롤백**
   - Vercel Dashboard → Deployments에서 이전 배포 버전으로 즉시 롤백 가능
   - 배포 히스토리 영구 보존

---

## 7. 성능 측정 도구

### 7.1 개발 중 측정

- **Chrome DevTools Lighthouse**: 로컬 성능 측정
- **Web Vitals Extension**: 실시간 Core Web Vitals 모니터링
- **React DevTools Profiler**: 컴포넌트 렌더링 성능 분석

### 7.2 프로덕션 측정

- **Vercel Analytics**: 실제 사용자 성능 데이터
- **Google Search Console**: SEO 및 Core Web Vitals
- **PageSpeed Insights**: 페이지 성능 점수

---

## 8. 성능 체크리스트

### 8.1 이미지 최적화
- [ ] `next/image` 사용
- [ ] WebP/AVIF 포맷 지원
- [ ] Lazy Loading 적용
- [ ] 이미지 크기 최적화 (적절한 해상도)
- [ ] CDN 사용

### 8.2 코드 최적화
- [ ] Dynamic Import로 코드 스플리팅
- [ ] Tree Shaking 적용
- [ ] 번들 사이즈 200KB 미만
- [ ] 미사용 의존성 제거

### 8.3 렌더링 최적화
- [ ] SSR/SSG 적절히 활용
- [ ] React Server Components 사용
- [ ] Suspense Boundary 적용
- [ ] Hydration 최적화

### 8.4 SEO 최적화
- [ ] 메타 태그 완성
- [ ] OG 태그 설정
- [ ] Sitemap 생성
- [ ] robots.txt 설정
- [ ] Structured Data (JSON-LD) 적용

### 8.5 Core Web Vitals
- [ ] LCP < 2.5s
- [ ] FID < 100ms
- [ ] CLS < 0.1
- [ ] TTFB < 600ms

---

## 9. 참조 문서

- **Next.js 공식 문서**: https://nextjs.org/docs
- **Vercel Analytics**: https://vercel.com/analytics
- **Web Vitals**: https://web.dev/vitals/
- **페이지 구조**: [01_pages.md](./01_pages.md)
- **컴포넌트 설계**: [02_components.md](./02_components.md)
