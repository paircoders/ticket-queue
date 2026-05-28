import type { Metadata, Viewport } from 'next'
import './globals.css'
import { Providers } from '@/providers'
import { getSiteUrl } from '@/lib/seo/site'

const SITE_URL = getSiteUrl()
const DESCRIPTION = '대기열 시스템으로 공정한 티켓 예매를 경험하세요.'

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: {
    default: 'Ticket Queue | 공정한 티켓팅 플랫폼',
    template: '%s | Ticket Queue',
  },
  description: DESCRIPTION,
  keywords: ['티켓팅', '공연', '콘서트', '예매', '대기열', 'K-POP'],
  applicationName: 'Ticket Queue',
  authors: [{ name: 'Ticket Queue' }],
  formatDetection: { telephone: false, address: false, email: false },
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
    siteName: 'Ticket Queue',
    title: 'Ticket Queue | 공정한 티켓팅 플랫폼',
    description: DESCRIPTION,
    url: SITE_URL,
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Ticket Queue | 공정한 티켓팅 플랫폼',
    description: DESCRIPTION,
  },
  alternates: {
    canonical: SITE_URL,
  },
}

export const viewport: Viewport = {
  themeColor: '#ffffff',
  width: 'device-width',
  initialScale: 1,
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="ko">
      <head>
        <link
          rel="preconnect"
          href="https://cdn.jsdelivr.net"
          crossOrigin="anonymous"
        />
        <link
          rel="stylesheet"
          as="style"
          crossOrigin="anonymous"
          href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css"
        />
      </head>
      <body className="antialiased">
        <Providers>{children}</Providers>
      </body>
    </html>
  )
}
