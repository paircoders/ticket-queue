# User Service API Specification
User Service는 회원 관리, 인증 및 프로필 관리를 담당

- **Base URL:** `/auth`, `/users`

## 1. 인증 (Auth)

### 1.1 회원가입
신규 회원 등록 (reCAPTCHA 토큰과 PortOne 본인인증 ID가 포함되어야 함. CI/DI는 서버에서 직접 조회함)

- **URL:** `POST /auth/signup`
- **Auth:** None

**Request Body**
```json
{
  "email": "user@example.com",
  "password": "strong_password123!",
  "name": "홍길동",
  "phone": "010-1234-5678",
  "identityVerificationId": "portone_identity_verification_id",
  "recaptchaToken": "recaptcha_response_token"
}
```

**Response (201 Created)**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "name": "홍길동"
}
```

**Error Responses**
- `400 Bad Request`: 필수 값 누락 또는 형식 오류
- `409 Conflict`: 이미 존재하는 이메일 (`ALREADY_EXISTS_EMAIL`)
- `409 Conflict`: 이미 가입된 본인인증 정보 (`DUPLICATE_IDENTITY`)
- `400 Bad Request`: 본인인증 정보 누락 (`PORTONE_MISSING_REQUIRED_INFO`)
- `400 Bad Request`: 본인인증 실패/미완료 (`PORTONE_VERIFICATION_FAILED`, `PORTONE_VERIFICATION_TIMEOUT`)
- `502 Bad Gateway`: PortOne API 연동 오류 (`PORTONE_API_ERROR`)

### 1.2 로그인
이메일과 비밀번호로 로그인하고 JWT 토큰 발급

- **URL:** `POST /auth/login`
- **Auth:** None

**Request Body**
```json
{
  "email": "user@example.com",
  "password": "strong_password123!",
  "recaptchaToken": "recaptcha_response_token"
}
```

**Response (200 OK)**
```json
{
  "accessToken": "eyJh... (JWT)",
  "refreshToken": "eyJh... (JWT)",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}
```

### 1.3 로그아웃
로그아웃을 수행하고 Access Token을 블랙리스트에 등록

- **URL:** `POST /auth/logout`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (204 No Content)**

### 1.4 토큰 갱신
Refresh Token을 사용하여 새로운 Access Token 발급 (RTR 적용)

- **URL:** `POST /auth/refresh`
- **Auth:** None

**Request Body**
```json
{
  "refreshToken": "existing_refresh_token"
}
```

**Response (200 OK)**
```json
{
  "accessToken": "new_access_token",
  "refreshToken": "new_refresh_token",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}
```

## 2. 사용자 (Users)

### 2.1 내 프로필 조회
- **URL:** `GET /users/me`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (200 OK)**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "name": "홍길동",
  "phone": "010-1234-5678",
  "role": "USER",
  "createdAt": "2026-01-01T00:00:00"
}
```

### 2.2 프로필 수정
- **URL:** `PATCH /users/me`
- **Headers:** `Authorization: Bearer {accessToken}`

**Request Body**
```json
{
  "name": "김철수",
  "phone": "010-9876-5432"
}
```

**Response (200 OK)**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "김철수",
  "phone": "010-9876-5432"
}
```

### 2.3 비밀번호 변경
- **URL:** `PUT /users/me/password`
- **Headers:** `Authorization: Bearer {accessToken}`

**Request Body**
```json
{
  "currentPassword": "old_password",
  "newPassword": "new_secure_password"
}
```

**Response (204 No Content)**

### 2.4 회원 탈퇴
- **URL:** `DELETE /users/me`
- **Headers:** `Authorization: Bearer {accessToken}`

**Response (204 No Content)**
