# Reservation Service API Specification
Reservation Service는 좌석 선점 및 예매를 관리함

- **Base URL:** `/reservations`

## 1. 좌석 선점 (Hold)

### 1.1 실시간 좌석 상태 조회
Redis HOLD 상태와 Event Service의 SOLD 상태를 병합하여 반환

- **URL:** `GET /reservations/seats/{scheduleId}`
- **Headers:**
  - `Authorization: Bearer {accessToken}`
  - `X-Queue-Token: {queueToken}`

**Response (200 OK)**
```json
{
  "scheduleId": "schedule_uuid",
  "seats": [
    { "seatId": "seat_1", "status": "AVAILABLE" },
    { "seatId": "seat_2", "status": "SOLD" },
    { "seatId": "seat_3", "status": "HOLD" }
  ]
}
```

### 1.2 좌석 선점 (임시 예매)
좌석을 5분간 선점 (최대 4매)

- **URL:** `POST /reservations/hold`
- **Headers:**
  - `Authorization: Bearer {accessToken}`
  - `X-Queue-Token: {queueToken}`

**Request Body**
```json
{
  "scheduleId": "schedule_uuid",
  "seatIds": ["seat_1", "seat_4"]
}
```

**Response (201 Created)**
```json
{
  "reservationId": "reservation_uuid",
  "status": "PENDING",
  "totalAmount": 300000,
  "holdExpiresAt": "2026-06-01 20:05:00"
}
```

**Error Responses**
...
### 1.3 선점 좌석 변경
- **URL:** `PUT /reservations/hold/{reservationId}`
- **Headers:** `Authorization`, `X-Queue-Token`

**Request Body**
```json
{
  "newSeatIds": ["seat_5", "seat_6"]
}
```

**Response (200 OK)**
```json
{
  "reservationId": "reservation_uuid",
  "status": "PENDING",
  "newTotalAmount": 300000,
  "holdExpiresAt": "2026-06-01 20:05:00"
}
```

### 1.4 선점 해제 (취소)
결제 전 선점된 좌석을 명시적으로 해제

- **URL:** `DELETE /reservations/hold/{reservationId}`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "message": "Hold released successfully"
}
```

## 2. 예매 내역 (My Reservations)

### 2.1 내 예매 내역 조회
- **URL:** `GET /reservations`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "content": [
    {
      "reservationId": "reservation_uuid",
      "eventTitle": "2026 월드 투어",
      "scheduleDate": "2026-06-01 19:00:00",
      "status": "CONFIRMED",
      "seats": [
        { "seatNumber": "A-1", "grade": "VIP" }
      ],
      "paymentAmount": 150000
    }
  ]
}
```

### 2.2 예매 상세 조회
- **URL:** `GET /reservations/{id}`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "reservationId": "reservation_uuid",
  "eventTitle": "2026 월드 투어",
  "artist": "인기 가수",
  "venueName": "잠실 주경기장",
  "hallName": "메인 홀",
  "scheduleDate": "2026-06-01 19:00:00",
  "status": "CONFIRMED",
  "seats": [
    { "seatId": "seat_1", "seatNumber": "A-1", "grade": "VIP", "price": 150000 }
  ],
  "totalAmount": 150000,
  "paymentId": "payment_uuid",
  "ticketNumber": "TKT-12345-67890",
  "qrData": "https://ticket-queue.com/tickets/verify/qr_data_string",
  "createdAt": "2026-05-01 20:00:00"
}
```

### 2.3 예매 취소
- **URL:** `DELETE /reservations/{id}`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "id": "reservation_uuid",
  "status": "CANCELLED",
  "refundAmount": 150000
}
```
