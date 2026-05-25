import { getSiteUrl } from '../site'

describe('getSiteUrl', () => {
  const origEnv = process.env

  beforeEach(() => {
    process.env = { ...origEnv }
    delete process.env.NEXT_PUBLIC_SITE_URL
    delete process.env.VERCEL_URL
  })

  afterAll(() => {
    process.env = origEnv
  })

  it('NEXT_PUBLIC_SITE_URL이 설정되면 그 값을 우선 사용한다', () => {
    process.env.NEXT_PUBLIC_SITE_URL = 'https://custom.example.com/'
    expect(getSiteUrl()).toBe('https://custom.example.com')
  })

  it('VERCEL_URL이 있으면 https:// prefix를 붙여 반환한다', () => {
    process.env.VERCEL_URL = 'preview-abc.vercel.app'
    expect(getSiteUrl()).toBe('https://preview-abc.vercel.app')
  })

  it('둘 다 없으면 기본 URL을 반환한다', () => {
    expect(getSiteUrl()).toBe('https://ticket-queue.com')
  })

  it('NEXT_PUBLIC_SITE_URL이 빈 문자열이면 fallback을 적용한다', () => {
    process.env.NEXT_PUBLIC_SITE_URL = '   '
    process.env.VERCEL_URL = 'fallback.vercel.app'
    expect(getSiteUrl()).toBe('https://fallback.vercel.app')
  })
})
