import { useEffect, useState } from 'react';
import { aiApi, type AiSettings as Settings } from '../api/ai';
import { HttpError } from '../api/client';

/**
 * 模型设置弹窗。
 *
 * <p>这个弹窗承担一件产品上很关键的事：让用户明白「Key 存在哪、谁能看到」。
 * 所以文案直说三条——只存你自己的账号、服务端加密、接口只回掩码。
 * 用户把付费凭据交出来时，含糊其辞比不加密更伤人。
 */
export function AiSettings({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [busy, setBusy] = useState<'load' | 'save' | 'test' | 'clear' | null>(null);
  const [message, setMessage] = useState<{ tone: 'ok' | 'err' | 'info'; text: string } | null>(null);

  useEffect(() => {
    if (!open) return;
    setMessage(null);
    setApiKey('');
    setBusy('load');
    aiApi
      .get()
      .then((s) => {
        setSettings(s);
        setBaseUrl(s.baseUrl);
        setModel(s.model);
      })
      .catch((e: unknown) => setMessage({ tone: 'err', text: describe(e) }))
      .finally(() => setBusy(null));
  }, [open]);

  if (!open) return null;

  const preset = settings?.presets.find((p) => p.id === settings.provider);

  const save = async () => {
    setBusy('save');
    setMessage(null);
    try {
      const next = await aiApi.save({
        provider: settings?.provider,
        baseUrl: baseUrl.trim(),
        model: model.trim(),
        apiKey: apiKey.trim() || undefined,
      });
      setSettings(next);
      setApiKey('');
      setMessage({ tone: 'ok', text: '已保存。点「测试连接」确认 Key 能用。' });
    } catch (e) {
      setMessage({ tone: 'err', text: describe(e) });
    } finally {
      setBusy(null);
    }
  };

  const test = async () => {
    setBusy('test');
    setMessage({ tone: 'info', text: '正在连接模型服务…' });
    try {
      const r = await aiApi.test();
      setMessage(
        r.ok
          ? { tone: 'ok', text: `连接正常，往返 ${r.latencyMs} ms（${r.model}）` }
          : { tone: 'err', text: r.error || '连接失败' },
      );
    } catch (e) {
      setMessage({ tone: 'err', text: describe(e) });
    } finally {
      setBusy(null);
    }
  };

  const clear = async () => {
    setBusy('clear');
    try {
      const next = await aiApi.save({ provider: settings?.provider, clearKey: true });
      setSettings(next);
      setApiKey('');
      setMessage({ tone: 'ok', text: '已清除保存的 Key。' });
    } catch (e) {
      setMessage({ tone: 'err', text: describe(e) });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="modal-mask" onPointerDown={(e) => e.stopPropagation()} onClick={onClose}>
      <div className="modal ai-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h2>AI 设置</h2>
          <p>
            用自己的 Key 调模型，费用由模型服务商直接计费。Key 只存在你自己账号下，
            服务端用 AES-GCM 加密保存，任何接口都不会返回明文。
          </p>
        </div>

        {settings && !settings.keyEncryptionReady && (
          <div className="ai-warn">
            服务端未配置 <code>APP_ENC_KEY</code>，现在保存 Key 会失败。请让部署者生成一个
            32 字节的 Base64 密钥填进环境变量后重启。
          </div>
        )}

        <div className="modal-row">
          <label>
            服务商
            <select
              value={settings?.provider ?? ''}
              onChange={(e) => {
                const p = settings?.presets.find((x) => x.id === e.target.value);
                if (!p) return;
                setSettings((s) => (s ? { ...s, provider: p.id } : s));
                setBaseUrl(p.baseUrl);
                setModel(p.model);
              }}
            >
              {settings?.presets.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            模型
            <input
              list="ai-model-options"
              value={model}
              onChange={(e) => setModel(e.target.value)}
              placeholder="deepseek-chat"
            />
            <datalist id="ai-model-options">
              {preset?.models.map((m) => (
                <option key={m} value={m} />
              ))}
            </datalist>
          </label>
        </div>

        <div className="modal-row">
          <label>
            接口地址
            <input
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://api.deepseek.com"
              spellCheck={false}
            />
          </label>
        </div>

        <div className="modal-row">
          <label>
            API Key
            <input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              placeholder={settings?.hasKey ? `已保存 ${settings.apiKeyMask}，留空则不修改` : 'sk-…'}
              spellCheck={false}
              autoComplete="off"
            />
          </label>
        </div>

        <div className="ai-meta">
          <span className={`ai-state ${settings?.hasKey ? 'on' : ''}`}>
            {settings?.hasKey ? `已配置 · ${settings.apiKeyMask}` : '尚未配置 Key'}
          </span>
          <a
            className="ai-link"
            href="https://platform.deepseek.com/api_keys"
            target="_blank"
            rel="noreferrer"
          >
            获取 DeepSeek Key ↗
          </a>
        </div>

        {message && <div className={`ai-msg ${message.tone}`}>{message.text}</div>}

        <div className="modal-foot">
          <span>地址必须是公网 https；内网与本机地址会被拒绝。</span>
          <div>
            {settings?.hasKey && (
              <button className="tb-btn" onClick={clear} disabled={busy !== null}>
                清除 Key
              </button>
            )}
            <button
              className="tb-btn"
              onClick={test}
              disabled={busy !== null || !settings?.hasKey}
              title={settings?.hasKey ? '用已保存的配置发一条最短请求' : '先保存 Key 才能测试'}
            >
              {busy === 'test' ? '测试中…' : '测试连接'}
            </button>
            <button className="tb-primary" onClick={save} disabled={busy === 'load' || busy === 'save'}>
              {busy === 'save' ? '保存中…' : '保存'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function describe(e: unknown): string {
  if (e instanceof HttpError) return e.message;
  return e instanceof Error ? e.message : '操作失败，请稍后重试';
}
