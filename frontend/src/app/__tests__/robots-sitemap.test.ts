describe('robots() / sitemap()', () => {
  const origEnv = process.env

  beforeEach(() => {
    jest.resetModules()
    process.env = {
      ...origEnv,
      NEXT_PUBLIC_SITE_URL: 'https://test.ticket-queue.com',
    }
  })

  afterAll(() => {
    process.env = origEnv
  })

  it('robots()는 보호 경로를 disallow에 포함하고 sitemap URL을 노출한다', async () => {
    const { default: robots } = await import('../robots')
    const result = robots()
    const rules = Array.isArray(result.rules) ? result.rules[0] : result.rules
    expect(rules?.disallow).toEqual(
      expect.arrayContaining([
        '/api/',
        '/mypage',
        '/payment',
        '/queue/*',
        '/reservation/*',
      ])
    )
    expect(result.sitemap).toBe('https://test.ticket-queue.com/sitemap.xml')
    expect(result.host).toBe('https://test.ticket-queue.com')
  })

  it('sitemap()은 공개 페이지만 노출한다', async () => {
    const { default: sitemap } = await import('../sitemap')
    const entries = sitemap()
    const urls = entries.map((entry) => entry.url)
    expect(urls).toEqual(
      expect.arrayContaining([
        'https://test.ticket-queue.com/',
        'https://test.ticket-queue.com/events',
        'https://test.ticket-queue.com/login',
        'https://test.ticket-queue.com/signup',
      ])
    )
    expect(urls).not.toEqual(expect.arrayContaining(['/mypage', '/payment']))
  })
})
