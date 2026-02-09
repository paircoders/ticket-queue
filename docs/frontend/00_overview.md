# 📱 프론트엔드 개요

## 1. 프론트엔드 목적

**Ticket Queue 프론트엔드**는 대규모 트래픽 환경에서 공정하고 안정적인 티켓팅 경험을 제공하는 것을 목표로 합니다.

### 핵심 가치
- **공정성**: 대기열 시스템으로 선착순 공정성 시각화
- **직관성**: 복잡한 예매 프로세스를 단순하고 명확한 UX로 제공
- **안정성**: 대규모 동시 접속 상황에서도 안정적인 사용자 경험
- **접근성**: 웹 접근성 기준(WCAG 2.1 AA) 준수

---

## 2. 기술 스택

### 2.1 프레임워크 및 라이브러리

| 기술 | 버전   | 용도 |
|------|------|------|
| **Next.js** | 16+  | React 기반 SSR/CSR 프레임워크, App Router |
| **React** | 19+  | UI 컴포넌트 라이브러리 |
| **TypeScript** | 5.6+ | 타입 안전성 |
| **TailwindCSS** | 4.0+ | 유틸리티 CSS 프레임워크 |
| **React Query (TanStack Query)** | 5.x  | Server State 관리, 캐싱 |
| **Zustand** | 5.x  | Client State 관리 (경량) |
| **React Hook Form** | 7.x  | 폼 관리 및 검증 |
| **Zod** | 3.x  | 스키마 검증 |
| **Axios** | 1.x  | HTTP 클라이언트 |

### 2.2 UI 컴포넌트 및 디자인

| 기술 | 용도 |
|------|------|
| **shadcn/ui** | Radix UI 기반 접근성 준수 UI 컴포넌트 시스템 (TailwindCSS 통합) |
| **Lucide React** | 아이콘 세트 |
| **Framer Motion** | 애니메이션 (선택) |
| **Recharts** | 차트 라이브러리 (관리자 대시보드) |

### 2.3 외부 서비스 연동

| 서비스 | 용도 |
|--------|------|
| **Google reCAPTCHA** | 봇 차단 (회원가입/로그인) |
| **PortOne SDK** | 본인인증(CI/DI), 결제 위젯 |
| **Vercel Analytics** | 성능 모니터링 |

### 2.4 개발 도구

| 도구 | 용도 |
|------|------|
| **ESLint** | 코드 린팅 |
| **Prettier** | 코드 포맷팅 |
| **Husky** | Git Hooks (커밋 전 린팅) |
| **Jest** | 단위 테스트 |
| **Playwright** | E2E 테스트 |

---

## 3. 설계 원칙

### 3.1 UX 우선 (UX First)
- **사용자 중심 설계**: 티켓팅 핵심 플로우에 집중
- **즉각적 피드백**: 로딩, 에러, 성공 상태를 명확히 시각화
- **에러 복구**: 에러 발생 시 사용자가 다음 행동을 쉽게 이해할 수 있도록 안내

### 3.2 접근성 (Accessibility)
- **키보드 네비게이션**: 모든 인터랙션 키보드로 접근 가능
- **스크린 리더 지원**: ARIA 속성 적극 활용
- **색상 대비**: WCAG AA 기준 준수 (최소 4.5:1)

### 3.3 반응형 디자인 (Responsive Design)
- **모바일 우선**: 모바일 → 태블릿 → 데스크톱 순서로 설계
- **브레이크포인트**:
  - Mobile: < 640px
  - Tablet: 640px ~ 1024px
  - Desktop: 1024px ~

### 3.4 성능 최적화
- **코드 스플리팅**: 라우트별 동적 임포트
- **이미지 최적화**: next/image 활용
- **번들 사이즈 최적화**: tree shaking, dynamic import
- **캐싱 전략**: React Query로 서버 상태 캐싱

### 3.5 보안
- **XSS 방지**: React 기본 이스케이핑 + DOMPurify (필요 시)
- **CSRF 방지**: httpOnly 쿠키 기반 JWT 토큰
- **입력 검증**: Zod 스키마로 클라이언트 측 검증
- **민감 정보 노출 방지**: 환경 변수로 API Key 관리

### 3.6 렌더링 전략 (CSR-First)

**설계 철학**: CSR(Client-Side Rendering)을 기본으로 하고, SSR(Server-Side Rendering)은 SEO가 필수적인 공개 페이지에만 적용합니다.

**SSR 적용 페이지**:
- `/` (홈): 공연 목록 SEO, 검색 엔진 노출
- `/events` (공연 목록): 공연 검색 및 크롤링
- `/events/[id]` (공연 상세): OG 태그 최적화, 소셜 미디어 공유

**CSR 적용 페이지**:
- `/login`, `/signup`: 인증 플로우, SEO 불필요
- `/queue/[scheduleId]`: 실시간 폴링, 동적 데이터
- `/reservation/[scheduleId]`: 실시간 좌석 상태, 동적 UI
- `/payment/[reservationId]`: 결제 위젯, 보안 민감 데이터
- `/mypage`: 사용자 개인 데이터, SEO 불필요

**설계 근거**:

1. **백엔드 부하 최소화**
   - SSR은 서버 측에서 매 요청마다 백엔드 API 호출을 유발합니다
   - 인증 필요 페이지(대기열, 예매, 결제)를 SSR로 구현하면 API Gateway → 백엔드 서비스로 추가 요청 발생
   - CSR은 브라우저에서 직접 API를 호출하므로 서버 측 오버헤드가 없음

2. **아키텍처 명확성**
   - CSR 방식은 브라우저 → API Gateway → 백엔드 서비스의 경로가 투명하게 드러남
   - SSR 방식은 Next.js 서버 → API Gateway → 백엔드 서비스로 경로가 복잡해짐
   - 프로젝트의 핵심은 백엔드 아키텍처(대기열, 분산 락, SAGA 패턴)이므로 프론트엔드 복잡도는 최소화

3. **프로젝트 초점**
   - 이 프로젝트의 목표는 대규모 트래픽을 처리하는 백엔드 시스템 검증입니다
   - 대기열 시스템, 분산 락, SAGA 패턴, Transactional Outbox 등이 핵심 기술
   - 프론트엔드는 이를 시연하기 위한 인터페이스 역할에 집중

---

## 4. 프로젝트 구조

```
frontend/
├── .next/                      # Next.js 빌드 결과물
├── public/                     # 정적 파일
│   ├── images/                 # 이미지 파일
│   └── fonts/                  # 폰트 파일 (선택)
├── src/
│   ├── app/                    # App Router (페이지 및 레이아웃)
│   │   ├── (auth)/             # 인증 관련 라우트 그룹
│   │   │   ├── login/
│   │   │   └── signup/
│   │   ├── (main)/             # 메인 라우트 그룹
│   │   │   ├── events/
│   │   │   ├── queue/
│   │   │   ├── reservation/
│   │   │   ├── payment/
│   │   │   └── mypage/
│   │   ├── layout.tsx          # 루트 레이아웃
│   │   ├── page.tsx            # 홈 페이지
│   │   ├── error.tsx           # 에러 바운더리
│   │   └── loading.tsx         # 로딩 UI
│   ├── components/             # 공통 컴포넌트
│   │   ├── ui/                 # UI 기본 컴포넌트
│   │   ├── layout/             # 레이아웃 컴포넌트
│   │   └── domain/             # 도메인 컴포넌트
│   ├── hooks/                  # Custom Hooks
│   ├── lib/                    # 유틸리티 함수
│   │   ├── api/                # API 호출 함수
│   │   ├── auth/               # 인증 관련 유틸
│   │   ├── portone/            # PortOne SDK Wrapper
│   │   └── validation/         # Zod 스키마
│   ├── stores/                 # Zustand 스토어
│   ├── types/                  # TypeScript 타입 정의
│   ├── styles/                 # 글로벌 스타일
│   └── middleware.ts           # Next.js 미들웨어
├── .env.local                  # 로컬 환경 변수
├── .env.production             # 프로덕션 환경 변수
├── next.config.mjs             # Next.js 설정
├── tailwind.config.ts          # TailwindCSS 설정
├── tsconfig.json               # TypeScript 설정
└── package.json                # 의존성 관리
```

---

## 5. 환경 변수 설정

### 5.1 `.env.local` (로컬 개발)

```bash
# API Gateway URL
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080

# reCAPTCHA (Google)
NEXT_PUBLIC_RECAPTCHA_SITE_KEY=your_site_key_here

# PortOne (테스트 모드)
NEXT_PUBLIC_PORTONE_IMP_CODE=imp_test_code
NEXT_PUBLIC_PORTONE_STORE_ID=test_store_id

# Vercel Analytics (선택)
NEXT_PUBLIC_VERCEL_ANALYTICS_ID=your_analytics_id
```

### 5.2 `.env.production` (프로덕션)

```bash
# API Gateway URL
NEXT_PUBLIC_API_BASE_URL=https://api.ticket-queue.com

# reCAPTCHA (Google)
NEXT_PUBLIC_RECAPTCHA_SITE_KEY=your_production_site_key

# PortOne (운영 모드)
NEXT_PUBLIC_PORTONE_IMP_CODE=imp_prod_code
NEXT_PUBLIC_PORTONE_STORE_ID=prod_store_id

# Vercel Analytics
NEXT_PUBLIC_VERCEL_ANALYTICS_ID=your_analytics_id
```

---

## 6. 백엔드 문서 참조

프론트엔드는 백엔드 API를 호출하여 데이터를 가져옵니다. 아래 문서들을 참조하세요:

### 6.1 API 명세서
- **공통 규약**: [`docs/specification/00_overview.md`](../specification/00_overview.md)
- **User Service API**: [`docs/specification/01_user_service.md`](../specification/01_user_service.md)
- **Event Service API**: [`docs/specification/02_event_service.md`](../specification/02_event_service.md)
- **Queue Service API**: [`docs/specification/03_queue_service.md`](../specification/03_queue_service.md)
- **Reservation Service API**: [`docs/specification/04_reservation_service.md`](../specification/04_reservation_service.md)
- **Payment Service API**: [`docs/specification/05_payment_service.md`](../specification/05_payment_service.md)

### 6.2 아키텍처 문서
- **시스템 개요**: [`docs/architecture/00_overview.md`](../architecture/00_overview.md)
- **데이터 아키텍처**: [`docs/architecture/04_data.md`](../architecture/04_data.md)
- **API 보안**: [`docs/architecture/06_api_security.md`](../architecture/06_api_security.md)

### 6.3 요구사항 명세서
- **전체 요구사항**: [`docs/REQUIREMENTS.md`](../REQUIREMENTS.md)

---

## 7. 개발 환경 실행

### 7.1 사전 요구사항
- **Node.js**: 24.13.0 (LTS)
- **pnpm**: 9+ (권장 패키지 매니저)

### 7.2 로컬 실행

```bash
# 의존성 설치
pnpm install

# 개발 서버 실행 (http://localhost:3000)
pnpm dev

# 프로덕션 빌드
pnpm build

# 프로덕션 서버 실행
pnpm start

# 린팅
pnpm lint

# 타입 체크
pnpm type-check

# 단위 테스트
pnpm test

# E2E 테스트
pnpm test:e2e
```

---

## 8. 배포

### 8.1 Vercel 배포 (권장)

**배포 프로세스**:

1. **GitHub 리포지토리 Import**
   - Vercel Dashboard → "Add New Project" → GitHub 리포지토리 선택
   - Frontend 디렉토리(`frontend/`) 지정

2. **빌드 설정**
   - Framework Preset: **Next.js**
   - Build Command: `pnpm build`
   - Output Directory: `.next` (자동 감지)
   - Install Command: `pnpm install`

3. **환경 변수 설정**
   - Vercel Dashboard → Settings → Environment Variables
   - 아래 환경 변수 입력 (Production, Preview, Development 환경별 설정 가능):
     ```
     NEXT_PUBLIC_API_BASE_URL=https://api.ticket-queue.com
     NEXT_PUBLIC_RECAPTCHA_SITE_KEY=your_production_site_key
     NEXT_PUBLIC_PORTONE_IMP_CODE=imp_prod_code
     NEXT_PUBLIC_PORTONE_STORE_ID=prod_store_id
     ```

4. **GitHub 연동 자동 CI/CD**
   - **`main` 브랜치 push → 프로덕션 자동 배포**
     - 커밋 푸시 후 자동으로 빌드 및 배포
     - 배포 URL: `https://ticket-queue.vercel.app` (커스텀 도메인 설정 가능)
   - **`develop` 브랜치 push → 스테이징 자동 배포** (선택)
     - Git Branch 기반 환경 분리 가능
   - **Pull Request 생성 → 프리뷰 배포 URL 자동 생성**
     - PR마다 고유한 프리뷰 URL 발급 (예: `https://ticket-queue-pr-123.vercel.app`)
     - PR 코멘트에 프리뷰 링크 자동 추가
     - 코드 리뷰 시 실제 동작 확인 가능

5. **배포 후 확인**
   - Vercel Dashboard → Deployments에서 배포 로그 확인
   - 빌드 실패 시 에러 로그 확인 및 수정

### 8.2 배포 체크리스트
- [ ] 환경 변수 모두 설정 완료
- [ ] 빌드 에러 없음 (`pnpm build`)
- [ ] 타입 에러 없음 (`pnpm type-check`)
- [ ] 린팅 에러 없음 (`pnpm lint`)
- [ ] E2E 테스트 통과 (`pnpm test:e2e`)
- [ ] Core Web Vitals 목표 달성 (Vercel Analytics)

---

## 9. 핵심 플로우 요약

| 플로우 | 주요 페이지 | 상세 문서 |
|--------|------------|----------|
| 회원가입 | 약관동의 → CAPTCHA → 본인인증 → 정보입력 | [04_auth_security.md](./04_auth_security.md) |
| 로그인 | 로그인 → CAPTCHA → JWT 발급 | [04_auth_security.md](./04_auth_security.md) |
| 공연 조회 | 공연 목록 → 공연 상세 → 좌석 정보 | [01_pages.md](./01_pages.md) |
| 대기열 | 대기열 진입 → 대기 → 승인 | [05_queue_ux.md](./05_queue_ux.md) |
| 예매 | 좌석 선택 → 좌석 선점 → 결제 → 완료 | [06_payment_ux.md](./06_payment_ux.md) |

---

## 10. 다음 단계

프론트엔드 상세 설계는 아래 문서들을 참조하세요:

1. **[01_pages.md](./01_pages.md)** - 페이지 구조 및 라우팅
2. **[02_components.md](./02_components.md)** - 컴포넌트 설계
3. **[03_state_data.md](./03_state_data.md)** - 상태 관리 및 데이터 흐름
4. **[04_auth_security.md](./04_auth_security.md)** - 인증/인가 흐름
5. **[05_queue_ux.md](./05_queue_ux.md)** - 대기열 UX 설계
6. **[06_payment_ux.md](./06_payment_ux.md)** - 결제 UX 설계
7. **[07_performance.md](./07_performance.md)** - 성능/SEO 전략
