import { getMockEventsPage, MOCK_EVENTS } from '@/lib/api/mock/events'

// MOCK_EVENTS 구성:
// OPEN:      mock-1(BTS), mock-2(BLACKPINK), mock-4(aespa), mock-7(EXO), mock-9(Stray Kids) → 5개
// PREPARING: mock-3(IU), mock-5(NewJeans), mock-8(TWICE) → 3개
// ENDED:     mock-6(SEVENTEEN) → 1개
// Total: 9개

describe('getMockEventsPage', () => {
  describe('필터 없음 — 기본 동작', () => {
    it('options 생략 시 전체 이벤트를 페이지네이션한다', () => {
      const result = getMockEventsPage(0, 3)
      expect(result.totalElements).toBe(MOCK_EVENTS.length)
      expect(result.list).toHaveLength(3)
    })

    it('page와 size를 응답에 포함한다', () => {
      const result = getMockEventsPage(1, 5)
      expect(result.page).toBe(1)
      expect(result.size).toBe(5)
    })
  })

  describe('keyword 필터링', () => {
    it('title에서 대소문자 구분 없이 keyword를 검색한다', () => {
      const result = getMockEventsPage(0, 10, { keyword: 'bts' })
      expect(result.list.every((e) => e.title.toLowerCase().includes('bts') || e.artist.toLowerCase().includes('bts'))).toBe(true)
      expect(result.totalElements).toBeGreaterThanOrEqual(1)
    })

    it('artist에서 keyword를 검색한다', () => {
      const result = getMockEventsPage(0, 10, { keyword: 'newjeans' })
      expect(result.list).toHaveLength(1)
      expect(result.list[0].artist).toBe('NewJeans')
      expect(result.totalElements).toBe(1)
    })

    it('title과 artist 양쪽에 걸쳐 검색한다 ("world" → title 기준)', () => {
      const result = getMockEventsPage(0, 10, { keyword: 'world' })
      // BTS World Tour, BLACKPINK BORN PINK World Tour, aespa MY WORLD Tour,
      // TWICE World Tour, STRAY KIDS MANIAC World Tour → 5개
      expect(result.totalElements).toBe(5)
    })

    it('매칭 결과 없으면 빈 list와 totalElements=0을 반환한다', () => {
      const result = getMockEventsPage(0, 10, { keyword: '존재하지않는검색어' })
      expect(result.list).toHaveLength(0)
      expect(result.totalElements).toBe(0)
    })

    it('빈 keyword는 필터링 없이 전체를 반환한다', () => {
      const result = getMockEventsPage(0, 20, { keyword: '' })
      expect(result.totalElements).toBe(MOCK_EVENTS.length)
    })
  })

  describe('status 필터링', () => {
    it('OPEN 상태만 반환한다 (5개)', () => {
      const result = getMockEventsPage(0, 10, { status: 'OPEN' })
      expect(result.totalElements).toBe(5)
      expect(result.list.every((e) => e.status === 'OPEN')).toBe(true)
    })

    it('PREPARING 상태만 반환한다 (3개)', () => {
      const result = getMockEventsPage(0, 10, { status: 'PREPARING' })
      expect(result.totalElements).toBe(3)
      expect(result.list.every((e) => e.status === 'PREPARING')).toBe(true)
    })

    it('ENDED 상태만 반환한다 (1개)', () => {
      const result = getMockEventsPage(0, 10, { status: 'ENDED' })
      expect(result.totalElements).toBe(1)
      expect(result.list[0].artist).toBe('SEVENTEEN')
    })

    it('존재하지 않는 status는 빈 결과를 반환한다', () => {
      const result = getMockEventsPage(0, 10, { status: 'UNKNOWN' })
      expect(result.list).toHaveLength(0)
      expect(result.totalElements).toBe(0)
    })
  })

  describe('복합 필터링 (keyword + status)', () => {
    it('keyword와 status를 AND 조건으로 필터링한다', () => {
      // "world" 매칭(5개) 중 OPEN인 것: BTS, BLACKPINK, aespa, TWICE(PREPARING), Stray Kids(OPEN)
      // TWICE는 PREPARING이므로 제외 → BTS(OPEN), BLACKPINK(OPEN), aespa(OPEN), Stray Kids(OPEN) = 4개
      const result = getMockEventsPage(0, 10, { keyword: 'world', status: 'OPEN' })
      expect(result.list.every((e) => e.status === 'OPEN')).toBe(true)
      expect(result.totalElements).toBe(4)
    })

    it('keyword 매칭이 있어도 status 불일치 시 빈 결과', () => {
      const result = getMockEventsPage(0, 10, { keyword: 'bts', status: 'ENDED' })
      expect(result.list).toHaveLength(0)
      expect(result.totalElements).toBe(0)
    })
  })

  describe('페이지네이션 정확성', () => {
    it('page=0 — 첫 번째 페이지를 반환한다', () => {
      const result = getMockEventsPage(0, 3)
      expect(result.list).toHaveLength(3)
      expect(result.list[0].id).toBe('mock-1')
      expect(result.page).toBe(0)
    })

    it('마지막 페이지 — 남은 항목만 반환한다', () => {
      // 9개, size=4 → page 0(4개), page 1(4개), page 2(1개)
      const result = getMockEventsPage(2, 4)
      expect(result.list).toHaveLength(1)
      expect(result.list[0].id).toBe('mock-9')
    })

    it('범위 초과 page — 빈 list를 반환한다', () => {
      const result = getMockEventsPage(100, 5)
      expect(result.list).toHaveLength(0)
      expect(result.totalElements).toBe(MOCK_EVENTS.length)
    })

    it('필터 적용 후 페이지네이션이 필터된 결과를 기준으로 동작한다', () => {
      // OPEN 5개, size=2 → page 0(2개), page 1(2개), page 2(1개)
      const page0 = getMockEventsPage(0, 2, { status: 'OPEN' })
      const page2 = getMockEventsPage(2, 2, { status: 'OPEN' })
      expect(page0.list).toHaveLength(2)
      expect(page2.list).toHaveLength(1)
      expect(page0.totalElements).toBe(5)
      expect(page2.totalElements).toBe(5)
    })
  })

  describe('totalElements 정확성', () => {
    it('필터 없을 때 totalElements는 전체 MOCK_EVENTS 수와 같다', () => {
      const result = getMockEventsPage(0, 1)
      expect(result.totalElements).toBe(9)
    })

    it('keyword 필터 적용 후 totalElements는 매칭된 수와 같다', () => {
      const result = getMockEventsPage(1, 2, { keyword: 'blackpink' })
      // BLACKPINK 1개 → page 1에는 항목 없음
      expect(result.totalElements).toBe(1)
      expect(result.list).toHaveLength(0)
    })

    it('status 필터 적용 후 totalElements는 해당 status 수와 같다', () => {
      const result = getMockEventsPage(1, 2, { status: 'PREPARING' })
      // PREPARING 3개, size=2 → page 1에 1개
      expect(result.totalElements).toBe(3)
      expect(result.list).toHaveLength(1)
    })
  })
})
