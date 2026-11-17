/** 后端错误响应体，与 Java 侧 ApiError 一一对应 */
export interface ApiErrorBody {
  code: string;
  message: string;
  field?: string | null;
}

export class HttpError extends Error {
  readonly status: number;
  readonly code: string;
  readonly field?: string;

  constructor(status: number, body: ApiErrorBody | null) {
    super(body?.message || '请求失败，请稍后重试');
    this.name = 'HttpError';
    this.status = status;
    this.code = body?.code || 'UNKNOWN';
    this.field = body?.field ?? undefined;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    // 会话在 httpOnly Cookie 里，必须带上凭据
    credentials: 'include',
    ...init,
    headers: {
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  });

  if (res.status === 204) {
    return undefined as T;
  }

  const raw = await res.text();
  let data: unknown = null;
  try {
    data = raw ? JSON.parse(raw) : null;
  } catch {
    // 后端异常时可能返回 HTML 错误页，不当作 JSON
    data = null;
  }

  if (!res.ok) {
    throw new HttpError(res.status, (data as ApiErrorBody) ?? null);
  }
  return data as T;
}

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
