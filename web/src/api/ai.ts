import { request } from './client';

export interface AiPreset {
  id: string;
  label: string;
  baseUrl: string;
  model: string;
  models: string[];
}

export interface AiSettings {
  provider: string;
  baseUrl: string;
  model: string;
  /** 只有后端存过 Key 才有值，形如 sk-****3f8a，永远不含明文 */
  apiKeyMask?: string | null;
  hasKey: boolean;
  /** 后端有没有配 APP_ENC_KEY；false 时保存 Key 会失败 */
  keyEncryptionReady: boolean;
  presets: AiPreset[];
}

export interface AiTestResult {
  ok: boolean;
  latencyMs: number;
  model?: string;
  error?: string;
}

export interface SaveAiSettingsInput {
  provider?: string;
  baseUrl?: string;
  model?: string;
  /** 留空 = 不修改已有 Key */
  apiKey?: string;
  clearKey?: boolean;
}

export const aiApi = {
  get: () => request<AiSettings>('/api/me/ai-settings'),

  save: (body: SaveAiSettingsInput) =>
    request<AiSettings>('/api/me/ai-settings', {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  test: () => request<AiTestResult>('/api/me/ai-settings/test', { method: 'POST' }),
};
