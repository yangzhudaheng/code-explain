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

/**
 * 统一请求封装。
 *
 * <p>会话在 httpOnly Cookie 里，必须带 credentials；204 不当 JSON 解析；
 * 后端异常时可能返回 HTML 错误页，解析失败不能让前端抛 SyntaxError。
 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
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
    data = null;
  }

  if (!res.ok) {
    throw new HttpError(res.status, (data as ApiErrorBody) ?? null);
  }
  return data as T;
}
