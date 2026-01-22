# Event Service API Specification
Event Service는 공연, 공연장, 좌석 정보를 관리하며 조회 성능을 위해 Redis 캐싱을 활용함

- **Base URL:** `/events`, `/venues`, `/internal`

## 1. 공연 (Events)

### 1.1 공연 생성 (관리자)
- **URL:** `POST /events`
- **Headers:** `Authorization: Bearer {adminToken}`

**Request Body**
```json
{
  "title": "2026 월드 투어 서울",
  "artist": "인기 가수",
  "description": "최고의 공연입니다.",
  "venueId": "venue_uuid",
  "hallId": "hall_uuid",
  "schedules": [
    {
      "playSequence": 1,
      "eventStartAt": "2026-06-01T19:00:00",
      "eventEndAt": "2026-06-01T22:00:00",
      "saleStartAt": "2026-05-01T20:00:00",
      "saleEndAt": "2026-05-31T23:59:59"
    }
  ]
}
```

**Response (201 Created)**
```json
{
  "id": "event_uuid",
  "title": "2026 월드 투어 서울",
  "artist": "인기 가수",
  "status": "DRAFT",
  "createdAt": "2026-01-20T10:00:00"
}
```

### 1.2 공연 목록 조회
- **URL:** `GET /events`
- **Auth:** None
- **Query Parameters:**
  - `page` (int, default: 0): 페이지 번호
  - `size` (int, default: 20): 페이지 크기
  - `status` (string, optional): 공연 상태 필터 (OPEN, CLOSED, UPCOMING)
  - `city` (string, optional): 도시 필터
**Response (200 OK)**
```json
{
  "list": [
    {
      "id": "event_uuid",
      "title": "2026 월드 투어 서울",
      "artist": "인기 가수",
      "venueName": "잠실 주경기장",
      "startDate": "2026-06-01T19:00:00",
      "endDate": "2026-06-02T19:00:00",
      "status": "OPEN"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 100
}
```

### 1.3 공연 상세 조회
- **URL:** `GET /events/{id}`
- **Auth:** None

**Response (200 OK)**
```json
{
  "id": "event_uuid",
  "title": "2026 월드 투어 서울",
  "artist": "인기 가수",
  "description": "...",
  "venue": { "id": "venue_uuid", "name": "잠실 주경기장" },
  "halls": { "id": "hall_uuid", "name": "메인 홀" },
  "schedules": [
    {
      "id": "schedule_uuid",
      "playSequence": 1,
      "eventStartAt": "2026-06-01T19:00:00",
      "saleStartAt": "2026-05-01T20:00:00",
      "status": "UPCOMING"
    }
  ]
}
```

### 1.4 회차별 좌석 정보 조회
- **URL:** `GET /events/schedules/{scheduleId}/seats`
- **Auth:** Bearer Token

**Response (200 OK)**
```json
{
  "scheduleId": "schedule_uuid",
  "grades": [
    {
      "grade": "VIP",
      "price": 150000,
      "seats": [
        { "id": "seat_1", "seatNumber": "A-1", "status": "AVAILABLE" },
        { "id": "seat_2", "seatNumber": "A-2", "status": "SOLD" }
      ]
    },
    {
      "grade": "S",
      "price": 120000,
      "seats": [...]
    }
  ]
}
```

### 1.5 공연 수정 (관리자)
- **URL:** `PUT /events/{id}`
- **Headers:** `Authorization: Bearer {adminToken}`

**Request Body**
```json
{
  "title": "수정된 공연 제목",
  "description": "수정된 설명"
}
```

**Response (200 OK)**
```json
{
  "id": "event_uuid",
  "title": "수정된 공연 제목",
  "description": "수정된 설명",
  "updatedAt": "2026-01-20T11:00:00"
}
```

### 1.6 공연 삭제 (관리자)
- **URL:** `DELETE /events/{id}`
- **Headers:** `Authorization: Bearer {adminToken}`

**Response (200 OK)**
```json
{
  "message": "Event deleted successfully"
}
```

## 2. 내부 API (Internal)
API Gateway를 거치지 않는 서비스 간 통신용

### 2.1 SOLD 좌석 조회
Reservation Service가 호출합니다.

- **URL:** `GET /internal/seats/status/{scheduleId}`
- **Headers:** `X-Service-Api-Key: {UUID}`

**Response (200 OK)**
```json
{
  "scheduleId": "schedule_uuid",
  "soldSeatIds": ["seat_2", "seat_5", "seat_99"]
}
```

## 3. 공연장 (Venues)

### 3.1 공연장 생성
- **URL:** `POST /venues`
- **Headers:** `Authorization: Bearer {adminToken}`

**Request Body**
```json
{
  "name": "잠실 주경기장",
  "address": "서울특별시 송파구 올림픽로 25",
  "city": "SEOUL"
}
```

**Response (201 Created)**
```json
{
  "id": "venue_uuid",
  "name": "잠실 주경기장",
  "address": "서울특별시 송파구 올림픽로 25",
  "city": "SEOUL",
  "createdAt": "2026-01-20T10:00:00"
}
```

### 3.2 홀 생성
- **URL:** `POST /venues/{venueId}/halls`
- **Headers:** `Authorization: Bearer {adminToken}`

**Request Body**
```json
{
  "name": "메인 홀",
  "capacity": 50000,
  "seatTemplate": {
    "rows": ["A", "B", "C"],
    "seatsPerRow": 20,
    "gradeMapping": { "A": "VIP", "B": "S", "C": "A" }
  }
}
```

**Response (201 Created)**
```json
{
  "id": "hall_uuid",
  "venueId": "venue_uuid",
  "name": "메인 홀",
  "capacity": 50000,
  "createdAt": "2026-01-20T10:00:00"
}
```

### 3.3 공연장 수정
- **URL:** `PUT /venues/{venueId}`
- **Headers:** `Authorization: Bearer {adminToken}`

**Request Body**
```json
{
  "name": "수정된 공연장명",
  "address": "수정된 주소"
}
```

**Response (200 OK)**
```json
{
  "id": "venue_uuid",
  "name": "수정된 공연장명",
  "address": "수정된 주소",
  "updatedAt": "2026-01-20T11:00:00"
}
```

### 3.4 공연장 삭제
- **URL:** `DELETE /venues/{venueId}`
- **Headers:** `Authorization: Bearer {adminToken}`

**Response (200 OK)**
```json
{
  "message": "Venue deleted successfully"
}
```

### 3.5 홀 수정
- **URL:** `PUT /venues/{venueId}/halls/{hallId}`
- **Headers:** `Authorization: Bearer {adminToken}`

**Request Body**
```json
{
  "name": "수정된 홀명",
  "capacity": 60000
}
```

**Response (200 OK)**
```json
{
  "id": "hall_uuid",
  "name": "수정된 홀명",
  "capacity": 60000,
  "updatedAt": "2026-01-20T11:00:00"
}
```

### 3.6 홀 삭제
- **URL:** `DELETE /venues/{venueId}/halls/{hallId}`
- **Headers:** `Authorization: Bearer {adminToken}`

**Response (200 OK)**
```json
{
  "message": "Hall deleted successfully"
}
```
