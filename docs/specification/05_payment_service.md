# Payment Service API Specification
Payment Service는 PortOne을 통한 결제 처리를 담당함

- **Base URL:** `/payments`

## 1. 결제 처리 (Payments)

### 1.1 결제 요청 (준비)
결제 정보를 생성하고 PortOne에 사전 등록(Prepare)을 수행

- **URL:** `POST /payments`
- **Headers:**
  - `Authorization: Bearer {accessToken}`
  - `X-Queue-Token: {queueToken}`

**Request Body**
```json
{
  "reservationId": "reservation_uuid",
  "amount": 300000,
  "paymentMethod": "CARD"
}
```

**Response (200 OK)**
```json
{
  "paymentId": "payment_uuid",
  "merchantUid": "payment_uuid",
  "amount": 300000,
  "storeId": "store-id-from-portone",
  "paymentKey": "generated-idempotency-key"
}
```
*프론트엔드는 이 정보를 사용하여 PortOne SDK 호출*

### 1.2 결제 승인 확인
PortOne SDK 결제 완료 후, 서버에 최종 승인 요청

- **URL:** `POST /payments/confirm`
- **Headers:**
  - `Authorization: Bearer {accessToken}`
  - `X-Queue-Token: {queueToken}`

**Request Body**
```json
{
  "paymentKey": "generated-idempotency-key",
  "reservationId": "reservation_uuid",
  "impUid": "imp_1234567890",
  "merchantUid": "payment_uuid",
  "amount": 300000
}
```

**Response (200 OK)**
```json
{
  "paymentId": "payment_uuid",
  "status": "SUCCESS",
  "paidAt": "2026-06-01 20:03:00"
}
```

**Error Responses**
- `400 Bad Request`: 결제 금액 불일치 (위변조 시도)
- `409 Conflict`: 이미 처리된 결제 (멱등성)

### 1.3 결제 상세 조회
- **URL:** `GET /payments/{id}`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "paymentId": "payment_uuid",
  "reservationId": "reservation_uuid",
  "amount": 300000,
  "status": "SUCCESS",
  "method": "CARD",
  "cardName": "SHINHAN",
  "cardNumber": "1234-****-****-5678"
}
```

### 1.4 내 결제 내역 조회
- **URL:** `GET /payments`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "content": [
    {
      "paymentId": "payment_uuid",
      "reservationId": "reservation_uuid",
      "amount": 300000,
      "status": "SUCCESS",
      "method": "CARD",
      "paidAt": "2026-06-01 20:03:00"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 5
}
```
