import { request } from '../../../services/http';
import { AuthResponse, LoginInput } from '../types/auth.types';

export function login(input: LoginInput): Promise<AuthResponse> {
  return request<AuthResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function getMe(): Promise<AuthResponse> {
  return request<AuthResponse>('/auth/me');
}

export function logout(): Promise<{ success: true }> {
  return request<{ success: true }>('/auth/logout', {
    method: 'POST',
  });
}
