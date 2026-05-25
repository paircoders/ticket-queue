const DEFAULT_SITE_URL = 'https://ticket-queue.com'

export function getSiteUrl(): string {
  const env = process.env.NEXT_PUBLIC_SITE_URL?.trim()
  if (env && env.length > 0) {
    return env.replace(/\/$/, '')
  }
  if (process.env.VERCEL_URL) {
    return `https://${process.env.VERCEL_URL.replace(/\/$/, '')}`
  }
  return DEFAULT_SITE_URL
}
