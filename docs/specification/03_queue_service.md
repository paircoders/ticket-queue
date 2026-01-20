# Queue Service API Specification
Queue Service는 대규모 트래픽을 제어하기 위한 가상 대기열 시스템

- **Base URL:** `/queue`

## 1. 대기열 (Queue)

### 1.1 대기열 진입
사용자를 대기열에 등록

- **URL:** `POST /queue/enter`
- **Headers:** `Authorization: Bearer {accessToken}`

**Request Body**
```json
{
  "scheduleId": "schedule_uuid"
}
```

**Response (200 OK)**
```json
{
  "status": "WAITING",
  "scheduleId": "schedule_uuid",
  "rank": 150,
  "estimatedWaitTime": 15,
  "token": null
}
```
- `rank`: 현재 대기 순번 (내 앞에 149명 있음)
- `estimatedWaitTime`: 예상 대기 시간 (초)
- `token`: 아직 입장 차례가 아니므로 null

### 1.2 대기열 상태 조회
주기적(5초 권장)으로 폴링하여 상태 확인. 입장이 가능해지면 `token` 발급
- **URL:** `GET /queue/status`
- **Query Params:** `scheduleId=schedule_uuid`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK - Waiting)**
```json
{
  "status": "WAITING",
  "rank": 50,
  "estimatedWaitTime": 5,
  "token": null
}
```

**Response (200 OK - Active/Passed)**
```json
{
  "status": "ACTIVE",
  "rank": 0,
  "estimatedWaitTime": 0,
  "token": "qr_12345uuid..."
}
```
- `token`: 이 토큰(qr_...)을 사용하여 예매/결제 API를 호출해야 함 (`X-Queue-Token` 헤더)

### 1.3 대기열 이탈
사용자가 대기 취소

- **URL:** `DELETE /queue/leave`
- **Query Params:** `scheduleId=schedule_uuid`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "message": "Removed from queue"
}
```

## 2. 관리자 (Admin)

### 2.1 통계 조회
- **URL:** `GET /queue/admin/stats`
- **Headers:** `Authorization: Bearer {adminToken}`

**Response (200 OK)**
```json
{
  "schedule_uuid": {
    "waiting": 5000,
    "active": 1000,
    "tps": 10
  }
}
```
