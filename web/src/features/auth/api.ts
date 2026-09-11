import { del, get, post } from '@/lib/apiClient';
import type { LoginRequest, LoginResponse, RegisterRequest, UserVO } from './types';

/** POST /auth/register */
export function register(req: RegisterRequest): Promise<UserVO> {
  return post<UserVO>('/api/auth/register', req);
}

/** POST /auth/login */
export function login(req: LoginRequest): Promise<LoginResponse> {
  return post<LoginResponse>('/api/auth/login', req);
}

/** POST /auth/logout */
export function logout(): Promise<void> {
  return post<void>('/api/auth/logout');
}

/** GET /auth/me */
export function getMe(): Promise<UserVO> {
  return get<UserVO>('/api/auth/me');
}
