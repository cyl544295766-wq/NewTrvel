import axios from 'axios';

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:3000/api',
  withCredentials: true,
  timeout: 60000,
});

export interface ApiError {
  message: string | string[];
  statusCode?: number;
}

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiError | undefined;
    if (data?.message) {
      return Array.isArray(data.message) ? data.message.join('；') : data.message;
    }
    return error.message;
  }
  return String(error);
}

/**
 * 统一 401 处理：跳转登录页（携带回跳地址）。
 * /auth/** 的请求（login/register/me 初次探测）不触发跳转，由调用方自行处理。
 */
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    const url: string = error.config?.url ?? '';
    if (status === 401 && !url.includes('/auth/')) {
      const current = window.location.pathname + window.location.search;
      if (current !== '/login') {
        window.sessionStorage.setItem('travel-os:redirect', current);
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  },
);

export function consumeRedirect(): string | null {
  const target = window.sessionStorage.getItem('travel-os:redirect');
  window.sessionStorage.removeItem('travel-os:redirect');
  return target;
}
