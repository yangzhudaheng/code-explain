import { HttpError, type ApiErrorBody } from './client';

export interface AnchorPayload {
  startOffset?: number;
  endOffset?: number;
  quote: string;
  startLine: number;
  startChar?: number;
  endLine: number;
  endChar?: number;
}

export interface CreateQuestionInput {
  clientId: string;
  blockId: string;
  anchor: AnchorPayload;
  text: string;
  language?: string;
  title?: string;
  code?: string;
}

export interface AnswerInput {
  code?: string;
  language?: string;
  title?: string;
}

export interface StreamHandlers {
  onStart?: (info: { answerId: string; model: string }) => void;
  onDelta: (text: string) => void;
  onDone?: (usage: { model?: string; promptTokens?: number; completionTokens?: number }) => void;
  onError: (message: string, code: string) => void;
}

export const qaApi = {
  createQuestion: (canvasId: string, body: CreateQuestionInput) =>
    fetchJson<{ questionId: string; clientId: string }>(
      `/api/canvases/${canvasId}/questions`,
      {
        method: 'POST',
        body: JSON.stringify(body),
      },
    ),
};

/** 和 client.ts 的 request 同语义，但这里要拿原始 Response 才能读流。 */
async function rawFetch(path: string, init: RequestInit): Promise<Response> {
  const res = await fetch(path, {
    credentials: 'include',
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
  });
  if (!res.ok) {
    let body: ApiErrorBody | null = null;
    try {
      body = (await res.json()) as ApiErrorBody;
    } catch {
      body = null;
    }
    throw new HttpError(res.status, body);
  }
  return res;
}

async function fetchJson<T>(path: string, init: RequestInit): Promise<T> {
  const res = await rawFetch(path, init);
  return (await res.json()) as T;
}

/**
 * 消费回答的 SSE 流。
 *
 * <p>为什么不用 {@code EventSource}：它只能发 GET，而这里需要把「当前代码内容」一起带上去
 * （快照有防抖延迟，刚粘完代码立刻提问时快照里还没有它）。fetch + ReadableStream 是唯一选择。
 *
 * <p>解析按 {@code \n\n} 切帧：一个事件可能被 TCP 拆成几段到达，也可能一次到达多个事件。
 * 只在收到完整空行分隔后才解析，避免把半截 JSON 当数据。
 */
export async function streamAnswer(
  questionId: string,
  body: AnswerInput,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const res = await rawFetch(`/api/questions/${questionId}/answer`, {
    method: 'POST',
    body: JSON.stringify(body),
    headers: { Accept: 'text/event-stream' },
    signal,
  });

  if (!res.body) {
    handlers.onError('浏览器不支持流式响应', 'STREAM_UNSUPPORTED');
    return;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const dispatch = (frame: string) => {
    let event = 'message';
    const dataLines: string[] = [];
    for (const rawLine of frame.split(/\r?\n/)) {
      if (rawLine.startsWith('event:')) event = rawLine.slice(6).trim();
      else if (rawLine.startsWith('data:')) dataLines.push(rawLine.slice(5).trimStart());
    }
    if (dataLines.length === 0) return;

    let payload: unknown;
    try {
      payload = JSON.parse(dataLines.join('\n'));
    } catch {
      return;
    }

    if (event === 'delta') {
      const text = (payload as { text?: string }).text ?? '';
      if (text) handlers.onDelta(text);
    } else if (event === 'start') {
      handlers.onStart?.(payload as { answerId: string; model: string });
    } else if (event === 'done') {
      handlers.onDone?.(payload as { model?: string; promptTokens?: number; completionTokens?: number });
    } else if (event === 'error') {
      const err = payload as { message?: string; code?: string };
      handlers.onError(err.message || '生成回答失败', err.code || 'AI_UPSTREAM_ERROR');
    }
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      let sep = buffer.indexOf('\n\n');
      while (sep >= 0) {
        const frame = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        if (frame.trim()) dispatch(frame);
        sep = buffer.indexOf('\n\n');
      }
    }
    if (buffer.trim()) dispatch(buffer);
  } catch (e) {
    // 用户主动取消（换画布、关页面、删问答）不算错误
    if ((e as Error)?.name === 'AbortError') return;
    handlers.onError(e instanceof Error ? e.message : '连接中断', 'STREAM_ABORTED');
  } finally {
    reader.releaseLock();
  }
}
