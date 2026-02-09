# 🧩 컴포넌트 설계

## 1. 컴포넌트 분류

### 1.1 디렉토리 구조

```
src/components/
├── ui/                        # 기본 UI 컴포넌트 (shadcn/ui + TailwindCSS)
│   ├── Button.tsx
│   ├── Input.tsx
│   ├── Modal.tsx
│   ├── Toast.tsx
│   ├── Card.tsx
│   ├── Badge.tsx
│   ├── Spinner.tsx
│   └── ...
├── layout/                    # 레이아웃 컴포넌트
│   ├── Header.tsx
│   ├── Footer.tsx
│   ├── Navigation.tsx
│   └── Sidebar.tsx
└── domain/                    # 도메인 컴포넌트
    ├── auth/
    │   ├── LoginForm.tsx
    │   ├── SignupForm.tsx
    │   └── RecaptchaWidget.tsx
    ├── event/
    │   ├── EventCard.tsx
    │   ├── EventList.tsx
    │   ├── EventDetail.tsx
    │   └── SeatMap.tsx
    ├── queue/
    │   ├── QueueStatus.tsx
    │   ├── QueueTimer.tsx
    │   └── QueueProgress.tsx
    ├── reservation/
    │   ├── SeatSelector.tsx
    │   ├── ReservationSummary.tsx
    │   └── HoldTimer.tsx
    └── payment/
        ├── PaymentWidget.tsx
        ├── PaymentSummary.tsx
        └── PaymentComplete.tsx
```

---

## 2. 공통 UI 컴포넌트

### 2.1 Button

**책임**: 클릭 가능한 버튼 (다양한 변형)

**Props**:
```typescript
interface ButtonProps {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  disabled?: boolean
  loading?: boolean
  onClick?: () => void
  children: React.ReactNode
}
```

**변형**:
- `primary`: 주요 액션 (파란색)
- `secondary`: 보조 액션 (회색)
- `outline`: 테두리만 있는 버튼
- `ghost`: 배경 없는 버튼
- `danger`: 위험 액션 (빨간색)

**사용 예**:
```tsx
<Button variant="primary" size="lg" loading={isLoading}>
  예매하기
</Button>
```

### 2.2 Input

**책임**: 텍스트 입력 필드 (에러 메시지, 라벨 포함)

**Props**:
```typescript
interface InputProps {
  label?: string
  type?: 'text' | 'email' | 'password' | 'tel'
  placeholder?: string
  error?: string
  disabled?: boolean
  value?: string
  onChange?: (value: string) => void
}
```

**사용 예**:
```tsx
<Input
  label="이메일"
  type="email"
  placeholder="example@email.com"
  error={errors.email}
  value={email}
  onChange={setEmail}
/>
```

### 2.3 Modal

**책임**: 팝업 모달 (확인, 취소 버튼 포함)

**Props**:
```typescript
interface ModalProps {
  isOpen: boolean
  onClose: () => void
  title?: string
  children: React.ReactNode
  footer?: React.ReactNode
}
```

**사용 예**:
```tsx
<Modal
  isOpen={isOpen}
  onClose={() => setIsOpen(false)}
  title="예매 취소"
  footer={
    <>
      <Button variant="outline" onClick={() => setIsOpen(false)}>
        닫기
      </Button>
      <Button variant="danger" onClick={handleCancel}>
        취소하기
      </Button>
    </>
  }
>
  <p>정말 예매를 취소하시겠습니까?</p>
</Modal>
```

### 2.4 Toast

**책임**: 토스트 알림 (성공, 에러, 정보)

**Props**:
```typescript
interface ToastProps {
  type: 'success' | 'error' | 'info' | 'warning'
  message: string
  duration?: number // ms (기본 3000)
}
```

**사용 예**:
```tsx
toast.success('로그인에 성공했습니다.')
toast.error('예매에 실패했습니다. 다시 시도해주세요.')
```

### 2.5 Card

**책임**: 카드 형태 컨테이너

**Props**:
```typescript
interface CardProps {
  children: React.ReactNode
  className?: string
  onClick?: () => void
}
```

**사용 예**:
```tsx
<Card onClick={() => navigate(`/events/${event.id}`)}>
  <img src={event.image} alt={event.title} />
  <h3>{event.title}</h3>
  <p>{event.description}</p>
</Card>
```

---

## 3. 페이지별 주요 컴포넌트 트리

### 3.1 로그인 페이지 (`/login`)

```
LoginPage
├── LoginForm
│   ├── Input (이메일)
│   ├── Input (비밀번호)
│   ├── RecaptchaWidget
│   └── Button (로그인)
└── Link (회원가입)
```

### 3.2 회원가입 페이지 (`/signup`)

```
SignupPage
├── SignupSteps (약관 → CAPTCHA → 본인인증 → 정보입력)
│   ├── TermsStep
│   │   ├── Checkbox (서비스 이용약관)
│   │   ├── Checkbox (개인정보 수집 동의)
│   │   └── Button (다음)
│   ├── CaptchaStep
│   │   ├── RecaptchaWidget
│   │   └── Button (다음)
│   ├── VerifyStep
│   │   ├── PortoneWidget (본인인증)
│   │   └── Button (다음)
│   └── InfoStep
│       ├── Input (이메일)
│       ├── Input (비밀번호)
│       ├── Input (이름)
│       ├── Input (전화번호)
│       └── Button (가입완료)
└── ProgressBar (현재 단계 표시)
```

### 3.3 공연 목록 페이지 (`/events`)

```
EventsPage
├── EventFilter (필터링, 정렬)
│   ├── Select (정렬 기준)
│   ├── Input (검색)
│   └── Button (적용)
├── EventList
│   └── EventCard (공연 카드 * N)
│       ├── Image
│       ├── Title
│       ├── Date
│       └── Badge (상태)
└── Pagination
```

### 3.4 공연 상세 페이지 (`/events/[id]`)

```
EventDetailPage
├── EventDetail
│   ├── Image (포스터)
│   ├── Title
│   ├── Description
│   ├── Venue (공연장 정보)
│   └── ScheduleList (회차 목록)
│       └── ScheduleCard (회차 카드 * N)
│           ├── Date
│           ├── Time
│           ├── Badge (잔여석)
│           └── Button (예매하기)
└── SeatInfo (좌석 정보)
    └── SeatGrade (등급별 가격)
```

### 3.5 대기열 페이지 (`/queue/[scheduleId]`)

```
QueuePage
├── QueueStatus
│   ├── QueueProgress (진행 바)
│   ├── QueuePosition (현재 순서)
│   ├── EstimatedTime (예상 대기 시간)
│   └── QueueTimer (남은 시간)
└── QueueInfo (안내 메시지)
```

### 3.6 좌석 선택 페이지 (`/reservation/[scheduleId]`)

```
ReservationPage
├── SeatSelector
│   ├── SeatMap (좌석 맵)
│   │   └── Seat (개별 좌석 * N)
│   └── SeatLegend (범례)
├── ReservationSummary (선택한 좌석 요약)
│   ├── SelectedSeats
│   ├── TotalPrice
│   └── HoldTimer (5분 타이머)
└── Button (결제하기)
```

### 3.7 결제 페이지 (`/payment/[reservationId]`)

```
PaymentPage
├── PaymentSummary (예매 정보 요약)
│   ├── EventInfo
│   ├── SeatInfo
│   └── PriceInfo
├── PaymentWidget (PortOne SDK)
└── Button (결제하기)
```

### 3.8 마이페이지 (`/mypage`)

```
MyPage
├── UserProfile (프로필 요약)
│   ├── Avatar
│   ├── Name
│   └── Email
├── ReservationList (예매 내역)
│   └── ReservationCard (예매 카드 * N)
│       ├── EventInfo
│       ├── SeatInfo
│       ├── Badge (상태)
│       └── Button (상세 보기)
└── Navigation (프로필 관리, 설정)
```

---

## 4. 디자인 시스템 기초

### 4.1 색상 팔레트

| 색상 | Hex | 용도 |
|------|-----|------|
| **Primary** | #3B82F6 | 주요 액션, 링크 |
| **Secondary** | #6B7280 | 보조 텍스트, 비활성 상태 |
| **Success** | #10B981 | 성공 메시지, 확인 |
| **Warning** | #F59E0B | 경고 메시지 |
| **Danger** | #EF4444 | 에러, 삭제 액션 |
| **Background** | #F9FAFB | 배경색 |
| **Text** | #111827 | 기본 텍스트 |

### 4.2 타이포그래피

| 요소 | Font Size | Font Weight | Line Height |
|------|-----------|-------------|-------------|
| **H1** | 2.5rem (40px) | 700 (Bold) | 1.2 |
| **H2** | 2rem (32px) | 600 (SemiBold) | 1.3 |
| **H3** | 1.5rem (24px) | 600 (SemiBold) | 1.4 |
| **Body** | 1rem (16px) | 400 (Regular) | 1.5 |
| **Caption** | 0.875rem (14px) | 400 (Regular) | 1.4 |

### 4.3 간격 (Spacing)

| 크기 | Value | 용도 |
|------|-------|------|
| **xs** | 0.25rem (4px) | 아이콘-텍스트 간격 |
| **sm** | 0.5rem (8px) | 컴포넌트 내부 간격 |
| **md** | 1rem (16px) | 컴포넌트 간 간격 |
| **lg** | 1.5rem (24px) | 섹션 간 간격 |
| **xl** | 2rem (32px) | 페이지 레벨 간격 |

### 4.4 Border Radius

| 크기 | Value | 용도 |
|------|-------|------|
| **sm** | 0.25rem (4px) | Badge, Tag |
| **md** | 0.5rem (8px) | Button, Input, Card |
| **lg** | 1rem (16px) | Modal, Dialog |
| **full** | 9999px | Avatar, Pill Button |

---

## 5. Server Component vs Client Component 분류

> **CSR-First 원칙에 따른 분류**: Server Component는 SEO가 필수적인 공개 콘텐츠 페이지에만 적용합니다. 인증이 필요한 페이지(대기열, 예매, 결제)는 모두 Client Component로 구현하여 브라우저에서 직접 API를 호출하고, 백엔드 서버 측 부하를 최소화합니다.

| 컴포넌트 | 타입 | 이유 |
|---------|------|------|
| **EventList** | Server Component | SSR로 SEO 최적화 |
| **EventCard** | Server Component | 정적 컴포넌트, 상태 없음 |
| **EventDetail** | Server Component | SSR로 OG 태그 최적화 |
| **SeatSelector** | Client Component | 실시간 상태 변경 (좌석 선택) |
| **QueueStatus** | Client Component | 실시간 폴링, 타이머 |
| **PaymentWidget** | Client Component | PortOne SDK 통합 |
| **LoginForm** | Client Component | 폼 입력, 검증 |
| **SignupForm** | Client Component | 폼 입력, 검증 |
| **ReservationSummary** | Client Component | 동적 가격 계산 |

### 5.1 Server Component 예시

```tsx
// components/domain/event/EventList.tsx (Server Component)
import { getEvents } from '@/lib/api/events'
import EventCard from './EventCard'

export default async function EventList() {
  const events = await getEvents()

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      {events.map((event) => (
        <EventCard key={event.id} event={event} />
      ))}
    </div>
  )
}
```

### 5.2 Client Component 예시

```tsx
// components/domain/queue/QueueStatus.tsx (Client Component)
'use client'

import { useQueueStatus } from '@/hooks/useQueueStatus'

export default function QueueStatus({ scheduleId }: { scheduleId: string }) {
  const { data, isLoading } = useQueueStatus(scheduleId)

  if (isLoading) return <Spinner />

  return (
    <div>
      <h2>대기 순서: {data.position}번</h2>
      <p>예상 대기 시간: {data.estimatedTime}분</p>
      <QueueTimer expiresAt={data.expiresAt} />
    </div>
  )
}
```

---

## 6. 컴포넌트 설계 원칙

### 6.1 단일 책임 원칙 (SRP)
- 각 컴포넌트는 하나의 책임만 가져야 함
- 예: `EventCard`는 공연 카드 표시만, 클릭 이벤트는 부모에서 처리

### 6.2 Props Drilling 방지
- 3단계 이상 Props를 전달해야 하면 Context 또는 Zustand 사용
- 예: 사용자 인증 상태는 Context로 관리

### 6.3 재사용성
- 공통 UI 컴포넌트는 최대한 재사용 가능하도록 설계
- 도메인 컴포넌트는 특정 도메인에 종속되어도 OK

### 6.4 접근성 (a11y)
- 모든 인터랙티브 요소는 키보드로 접근 가능해야 함
- ARIA 속성 적극 활용 (aria-label, aria-describedby 등)

### 6.5 테스트 가능성
- 컴포넌트는 독립적으로 테스트 가능해야 함
- 외부 의존성(API, Context)은 Props로 주입

---

## 7. 참조 문서

- **페이지 구조**: [01_pages.md](./01_pages.md)
- **상태 관리**: [03_state_data.md](./03_state_data.md)
- **성능 최적화**: [07_performance.md](./07_performance.md)
