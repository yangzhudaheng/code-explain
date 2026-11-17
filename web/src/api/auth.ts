import { HttpError, request, type ApiErrorBody } from './client';

/** 老代码从 auth 里引 HttpError，这里转出去，避免每个引用点都改一遍 */
export { HttpError };
export type { ApiErrorBody };

export interface SessionUser {
  id: string;
  email: string;
  displayName?: string | null;
  emailVerified: boolean;
  createdAt: string;
}

export interface Captcha {
  id: string;
  image: string;
}

export interface AuthPolicy {
  emailCooldownSeconds: number;
  codeTtlMinutes: number;
  maxCodeAttempts: number;
  maxPerIpDaily: number;
  minPasswordLength: number;
}

export const authApi = {
  captcha: () => request<Captcha>('/api/auth/captcha'),

  policy: () => request<AuthPolicy>('/api/auth/policy'),

  sendEmailCode: (email: string, captchaId: string, captchaCode: string) =>
    request<{ sent: boolean; devMode: boolean; cooldownSeconds: number }>('/api/auth/email-code', {
      method: 'POST',
      body: JSON.stringify({ email, captchaId, captchaCode }),
    }),

  register: (input: { email: string; code: string; password: string; displayName?: string }) =>
    request<SessionUser>('/api/auth/register', { method: 'POST', body: JSON.stringify(input) }),

  login: (email: string, password: string) =>
    request<SessionUser>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),

  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),

  me: () => request<SessionUser>('/api/auth/me'),
};
