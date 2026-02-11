export interface SignupRequest {
  email: string
  password: string
  name: string
  phone: string
  ci: string
  di: string
  recaptchaToken: string
}

export interface SignupResponse {
  id: string
  email: string
  name: string
}

export interface LoginRequest {
  email: string
  password: string
  recaptchaToken: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  tokenType: string
}

export interface RefreshTokenRequest {
  refreshToken: string
}

export interface RefreshResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  tokenType: string
}

export interface User {
  id: string
  email: string
  name: string
  phone: string
  role: string
  createdAt: string
}

export interface UpdateProfileRequest {
  name?: string
  phone?: string
}

export interface UpdateProfileResponse {
  id: string
  name: string
  phone: string
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}
