import { apiClient } from './axios'
import type {
  SignupRequest,
  SignupResponse,
  LoginRequest,
  LoginResponse,
  RefreshTokenRequest,
  RefreshResponse,
  User,
  UpdateProfileRequest,
  UpdateProfileResponse,
  ChangePasswordRequest,
} from '@/types/auth'

export async function signup(data: SignupRequest): Promise<SignupResponse> {
  const response = await apiClient.post<SignupResponse>('/auth/signup', data)
  return response.data
}

export async function login(data: LoginRequest): Promise<LoginResponse> {
  const response = await apiClient.post<LoginResponse>('/auth/login', data)
  return response.data
}

export async function logout(): Promise<void> {
  await apiClient.post('/auth/logout')
}

export async function refreshToken(
  data: RefreshTokenRequest
): Promise<RefreshResponse> {
  const response = await apiClient.post<RefreshResponse>(
    '/auth/refresh',
    data
  )
  return response.data
}

export async function getMyProfile(): Promise<User> {
  const response = await apiClient.get<User>('/users/me')
  return response.data
}

export async function updateMyProfile(
  data: UpdateProfileRequest
): Promise<UpdateProfileResponse> {
  const response = await apiClient.patch<UpdateProfileResponse>(
    '/users/me',
    data
  )
  return response.data
}

export async function changePassword(
  data: ChangePasswordRequest
): Promise<void> {
  await apiClient.put('/users/me/password', data)
}

export async function deleteAccount(): Promise<void> {
  await apiClient.delete('/users/me')
}
