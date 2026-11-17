import { useEffect, useRef, useState } from 'react';
import { useCanvas } from '../store/canvasStore';
import { useSession } from '../store/sessionStore';
import { overwriteServer, reloadFromServer, retrySave, switchCanvas } from '../store/persist';

function clockText(ts: number | null) {
  if (!ts) return '';
  const d = new Date(ts);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

export function Sidebar() {
  const order = useCanvas((s) => s.order);
  const docs = useCanvas((s) => s.docs);
  const activeId = useCanvas((s) => s.activeId);
  const addCanvas = useCanvas((s) => s.addCanvas);
  const removeCanvas = useCanvas((s) => s.removeCanvas);
  const renameCanvas = useCanvas((s) => s.renameCanvas);
  const saveState = useCanvas((s) => s.saveState);
  const saveError = useCanvas((s) => s.saveError);
  const lastSavedAt = useCanvas((s) => s.lastSavedAt);

  const user = useSession((s) => s.user);
  const logout = useSession((s) => s.logout);

  const [renaming, setRenaming] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [confirmDel, setConfirmDel] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (renaming) inputRef.current?.select();
  }, [renaming]);

  const label = user?.displayName || user?.email || '';
  const initial = label.slice(0, 1).toUpperCase();

  const beginRename = (id: string) => {
    const doc = docs[id];
    if (!doc) return;
    setDraft(doc.title);
    setRenaming(id);
  };

  const commitRename = () => {
    if (renaming) renameCanvas(renaming, draft);
    setRenaming(null);
  };

  const onAdd = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await addCanvas();
    } catch (e) {
      // 上限或网络问题：错误交给全局保存状态那一条显示，这里不弹二次提示
      console.warn('新建画布失败', e);
    } finally {
      setBusy(false);
    }
  };

  const onDelete = async (id: string) => {
    setConfirmDel(null);
    try {
      await removeCanvas(id);
    } catch (e) {
      console.warn('删除画布失败', e);
    }
  };

  return (
    <aside className="sidebar" onPointerDown={(e) => e.stopPropagation()}>
      <div className="brand">
        <span className="brand-mark">CE</span>
        <div>
          <div className="brand-name">Code Explain</div>
          <div className="brand-sub">代码题解画布</div>
        </div>
      </div>

      <div className="side-section">
        <div className="side-title">
          画布
          <button className="side-add" onClick={() => void onAdd()} title="新建画布" disabled={busy}>
            ＋
          </button>
        </div>
        <ul className="doc-list">
          {order.map((id) => {
            const doc = docs[id];
            if (!doc) return null;
            if (confirmDel === id) {
              return (
                <li key={id} className="doc-confirm">
                  <span>删除这个画布？</span>
                  <button className="doc-mini danger" onClick={() => void onDelete(id)}>
                    删除
                  </button>
                  <button className="doc-mini" onClick={() => setConfirmDel(null)}>
                    取消
                  </button>
                </li>
              );
            }
            return (
              <li
                key={id}
                className={id === activeId ? 'active' : ''}
                onClick={() => void switchCanvas(id)}
                onDoubleClick={() => beginRename(id)}
                title="双击重命名"
              >
                <span className="doc-dot" />
                {renaming === id ? (
                  <input
                    ref={inputRef}
                    className="doc-rename"
                    value={draft}
                    autoFocus
                    onChange={(e) => setDraft(e.target.value)}
                    onClick={(e) => e.stopPropagation()}
                    onBlur={commitRename}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') commitRename();
                      if (e.key === 'Escape') setRenaming(null);
                    }}
                  />
                ) : (
                  <span className="doc-name">{doc.title}</span>
                )}
                <span className="doc-count">{doc.questions.length}</span>
                <button
                  className="doc-del"
                  title="删除画布"
                  onClick={(e) => {
                    e.stopPropagation();
                    setConfirmDel(id);
                  }}
                >
                  ×
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      <div className={`save-bar save-${saveState}`}>
        {saveState === 'saving' && <span>保存中…</span>}
        {saveState === 'pending' && <span>有改动待保存</span>}
        {saveState === 'saved' && <span>已保存 · {clockText(lastSavedAt)}</span>}
        {saveState === 'idle' && <span>就绪</span>}
        {saveState === 'conflict' && (
          <>
            <span>已在别处修改</span>
            <div className="save-actions">
              <button onClick={() => void reloadFromServer()}>重新加载</button>
              <button className="danger" onClick={() => void overwriteServer()}>
                以我为准
              </button>
            </div>
          </>
        )}
        {saveState === 'error' && (
          <>
            <span className="save-err">{saveError || '保存失败'}</span>
            <button onClick={() => void retrySave()}>重试</button>
          </>
        )}
      </div>

      <div className="side-tips">
        <p>· 拖标题栏整块移动代码</p>
        <p>· 选中代码任意片段 → 点 +</p>
        <p>· 空格拖拽或滚轮平移画布</p>
        <p>· Ctrl + 滚轮缩放</p>
        <p>· Ctrl+V 直接粘贴代码</p>
      </div>

      <div className="side-user">
        <span className="side-avatar">{initial}</span>
        <div className="side-user-info">
          <div className="side-user-name">{user?.displayName || '未命名'}</div>
          <div className="side-user-mail">{user?.email}</div>
        </div>
        <button
          className="side-logout"
          title="退出登录"
          onClick={() => void logout()}
        >
          退出
        </button>
      </div>
    </aside>
  );
}
