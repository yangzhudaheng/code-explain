import { request } from './client';

export interface CanvasSummary {
  id: string;
  title: string;
  version: number;
  updatedAt: string;
  blockCount: number;
  questionCount: number;
}

/** data 是前端自己定义的结构，后端只当 JSON 存，不在 TS 里重复描述一遍 schema */
export interface CanvasDetail {
  id: string;
  title: string;
  data: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export const canvasApi = {
  list: () => request<CanvasSummary[]>('/api/canvases'),

  get: (id: string) => request<CanvasDetail>(`/api/canvases/${id}`),

  create: (input: { title?: string; data?: unknown }) =>
    request<CanvasDetail>('/api/canvases', {
      method: 'POST',
      body: JSON.stringify(input),
    }),

  /** 全量保存；baseVersion 与服务端不一致时后端返回 409 CANVAS_VERSION_CONFLICT */
  save: (id: string, input: { title: string; data: unknown; baseVersion: number }) =>
    request<CanvasDetail>(`/api/canvases/${id}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }),

  remove: (id: string) => request<void>(`/api/canvases/${id}`, { method: 'DELETE' }),
};
