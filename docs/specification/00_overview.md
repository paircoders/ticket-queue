# API Specification Overview

## 1. 개요
Ticket Queue 프로젝트의 API 명세서 개요  

### 1.1 문서 목록
상세한 API 명세는 각 서비스별 문서에서 확인 가능

- [01_user_service.md](./01_user_service.md): 회원/인증 (Auth, Users)
- [02_event_service.md](./02_event_service.md): 공연/공연장 (Events, Venues)
- [03_queue_service.md](./03_queue_service.md): 대기열 (Queue)
- [04_reservation_service.md](./04_reservation_service.md): 예매/좌석 (Reservations)
- [05_payment_service.md](./05_payment_service.md): 결제 (Payments)

## 2. 공통 규약 (Conventions)

### 2.1 날짜 및 시간 형식
모든 날짜와 시간은 **yyyy-MM-ddTHH:mm:ss** 형식을 사용 (ISO 8601)
```json
"2026-01-20T10:00:00"
```

### 2.2 공통 에러 응답 (Error Response)
모든 API는 에러 발생 시 아래와 같은 통일된 JSON 형식을 반환

```json
{
  "code": "USER_NOT_FOUND",             // 의미 기반 에러 코드 
  "message": "존재하지 않는 사용자입니다.", // 사용자에게 팝업으로 보여줄 메시지
  "timestamp": "2026-01-20T10:00:00",   // 에러 발생 응답 일시
  "traceId": "0f12d345-6789-abcd-ef01-23456789abcd" // 분산 추적용 ID
}
```

### 2.3 인증 헤더 (Authorization)
보안이 필요한 API는 `Authorization` 헤더에 Bearer Token을 포함해야 함
```http
Authorization: Bearer {Access_Token}
```

### 2.4 대기열 토큰 헤더 (Queue Token)
트래픽 제어가 필요한 중요 API(예매, 결제 등)는 `X-Queue-Token` 헤더를 필수적으로 포함해야 함
```http
X-Queue-Token: {Queue_Token}
```

## 3. API 요약 (Summary)

### 3.1 User Service
| Method | Endpoint | 설명 | Auth |
|:---:|---|---|:---:|
| POST | `/auth/signup` | 회원가입 | X |
| POST | `/auth/login` | 로그인 | X |
| POST | `/auth/logout` | 로그아웃 | O |
| POST | `/auth/refresh` | 토큰 갱신 | X |
| GET | `/users/me` | 내 프로필 조회 | O |
| PUT | `/users/me` | 프로필 수정 | O |
| PUT | `/users/me/password` | 비밀번호 변경 | O |
| DELETE | `/users/me` | 회원 탈퇴 | O |

### 3.2 Event Service
| Method | Endpoint | 설명 | Auth |
|:---:|---|---|:---:|
| GET | `/events` | 공연 목록 조회 | X |
| GET | `/events/{id}` | 공연 상세 조회 | X |
| GET | `/events/schedules/{scheduleId}/seats` | 좌석 정보 조회 | X |
| POST | `/events` | 공연 생성 (Admin) | O |
| PUT | `/events/{id}` | 공연 수정 (Admin) | O |
| DELETE | `/events/{id}` | 공연 삭제 (Admin) | O |
| POST | `/venues` | 공연장 생성 (Admin) | O |
| PUT | `/venues/{id}` | 공연장 수정 (Admin) | O |
| DELETE | `/venues/{id}` | 공연장 삭제 (Admin) | O |
| POST | `/venues/{venueId}/halls` | 홀 생성 (Admin) | O |
| PUT | `/venues/{venueId}/halls/{hallId}` | 홀 수정 (Admin) | O |
| DELETE | `/venues/{venueId}/halls/{hallId}` | 홀 삭제 (Admin) | O |

### 3.3 Queue Service
| Method | Endpoint | 설명 | Auth |
|:---:|---|---|:---:|
| POST | `/queue/enter` | 대기열 진입 | O |
| GET | `/queue/status` | 대기열 상태 조회 (Token 발급) | O |
| DELETE | `/queue/leave` | 대기열 이탈 | O |
| GET | `/queue/admin/stats` | 대기열 통계 (Admin) | O |

### 3.4 Reservation Service
| Method | Endpoint | 설명 | Auth | Header |
|:---:|---|---|:---:|:---:|
| GET | `/reservations/seats/{scheduleId}` | 실시간 좌석 상태 조회 | O | Queue |
| POST | `/reservations/hold` | 좌석 선점 (임시 예매) | O | Queue |
| PUT | `/reservations/hold/{reservationId}` | 선점 좌석 변경 | O | Queue |
| DELETE | `/reservations/hold/{reservationId}` | 선점 해제 | O | |
| GET | `/reservations` | 내 예매 내역 조회 | O | |
| GET | `/reservations/{id}` | 예매 상세 조회 | O | |
| DELETE | `/reservations/{id}` | 예매 취소 | O | |

### 3.5 Payment Service
| Method | Endpoint | 설명 | Auth | Header |
|:---:|---|---|:---:|:---:|
| POST | `/payments` | 결제 요청 (사전 검증) | O | Queue |
| POST | `/payments/confirm` | 결제 승인 (최종 확정) | O | Queue |
| GET | `/payments/{id}` | 결제 상세 조회 | O | |
| GET | `/payments` | 내 결제 내역 조회 | O | |

## 4. HTTP 상태 코드 가이드
```
200 OK              - 성공 (조회, 수정)
201 Created         - 생성 성공 (Location 헤더 포함 권장)
204 No Content      - 성공했으나 반환할 내용 없음 (삭제)
400 Bad Request     - 잘못된 요청 (validation 실패)
401 Unauthorized    - 인증 실패
403 Forbidden       - 권한 없음
404 Not Found       - 리소스 없음
409 Conflict        - 중복 등 비즈니스 로직 충돌
422 Unprocessable   - 문법은 맞지만 처리 불가
429 Too Many Req    - Rate limit 초과
500 Internal Error  - 서버 오류
503 Service Unavail - 서비스 일시 불가