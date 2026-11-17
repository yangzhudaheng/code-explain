import { useEffect, useState } from 'react';
import { useCanvas } from '../store/canvasStore';
import { PATTERNS, swatchStyle, TONES } from '../canvas/background';

export function Toolbar() {
  const [open, setOpen] = useState(false);
  const [bgOpen, setBgOpen] = useState(false);
  const [code, setCode] = useState('');
  const [title, setTitle] = useState('Solution.java');
  const [lang, setLang] = useState('java');

  const zoom = useCanvas((s) => s.docs[s.activeId].viewport.zoom);
  const bg = useCanvas((s) => s.docs[s.activeId].bg);
  const setBg = useCanvas((s) => s.setBg);
  const zoomAt = useCanvas((s) => s.zoomAt);
  const resetView = useCanvas((s) => s.resetView);
  const arrangeAll = useCanvas((s) => s.arrangeAll);
  const addBlock = useCanvas((s) => s.addBlock);

  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t?.closest('input, textarea')) return;
      const text = e.clipboardData?.getData('text/plain');
      if (!text || text.trim().length < 8) return;
      e.preventDefault();
      setCode(text);
      setOpen(true);
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, []);

  // 点空白处收起背景面板
  useEffect(() => {
    if (!bgOpen) return;
    const onDown = (e: PointerEvent) => {
      const t = e.target as HTMLElement | null;
      if (t?.closest('.bg-pop') || t?.closest('.tb-bg')) return;
      setBgOpen(false);
    };
    window.addEventListener('pointerdown', onDown);
    return () => window.removeEventListener('pointerdown', onDown);
  }, [bgOpen]);

  const centerRect = () => {
    const el = document.querySelector('.canvas-root') as HTMLElement | null;
    return el?.getBoundingClientRect() ?? new DOMRect();
  };

  const zoomStep = (factor: number) => {
    const rect = centerRect();
    zoomAt(factor, rect.left + rect.width / 2, rect.top + rect.height / 2, rect);
  };

  const submit = () => {
    if (!code.trim()) return;
    addBlock({ content: code.replace(/\t/g, '    '), title: title.trim() || 'snippet.txt', language: lang });
    setCode('');
    setOpen(false);
  };

  return (
    <>
      <div className="toolbar" onPointerDown={(e) => e.stopPropagation()}>
        <button className="tb-primary" onClick={() => setOpen(true)}>
          ＋ 粘贴代码
        </button>
        <span className="tb-sep" />
        <button className="tb-btn" onClick={arrangeAll} title="重排所有未锁定的回答框">
          整理
        </button>
        <span className="tb-sep" />
        <div className="tb-bg-wrap">
          <button
            className={`tb-btn ${bgOpen ? 'on' : ''}`}
            onClick={() => setBgOpen((v) => !v)}
            title="切换画布背景"
          >
            背景
          </button>
          {bgOpen && (
            <div className="bg-pop" onPointerDown={(e) => e.stopPropagation()}>
              <div className="bg-pop-title">图案</div>
              <div className="bg-opts">
                {PATTERNS.map((p) => (
                  <button
                    key={p.id}
                    className={`bg-opt ${bg.pattern === p.id ? 'on' : ''}`}
                    onClick={() => setBg({ pattern: p.id })}
                  >
                    <span className="bg-swatch" style={swatchStyle(p.id, bg.tone)} />
                    <span>{p.label}</span>
                  </button>
                ))}
              </div>
              <div className="bg-pop-title">底色</div>
              <div className="bg-tones">
                {TONES.map((t) => (
                  <button
                    key={t.id}
                    className={`tone-opt ${bg.tone === t.id ? 'on' : ''}`}
                    onClick={() => setBg({ tone: t.id })}
                    title={t.label}
                  >
                    <span className="tone-dot" style={{ background: t.base }} />
                    <span>{t.label}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
        <span className="tb-sep" />
        <button className="tb-icon" onClick={() => zoomStep(1 / 1.2)} title="缩小">
          －
        </button>
        <span className="tb-zoom">{Math.round(zoom * 100)}%</span>
        <button className="tb-icon" onClick={() => zoomStep(1.2)} title="放大">
          ＋
        </button>
        <button className="tb-btn" onClick={resetView} title="回到初始视图">
          复位
        </button>
      </div>

      {open && (
        <div className="modal-mask" onPointerDown={(e) => e.stopPropagation()} onClick={() => setOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <h2>粘贴一段代码</h2>
              <p>整段代码会成为画布上的一个原子块，只能整体拖动。</p>
            </div>
            <div className="modal-row">
              <label>
                标题
                <input value={title} onChange={(e) => setTitle(e.target.value)} />
              </label>
              <label>
                语言
                <select value={lang} onChange={(e) => setLang(e.target.value)}>
                  <option value="java">java</option>
                  <option value="cpp">cpp</option>
                  <option value="python">python</option>
                  <option value="javascript">javascript</option>
                  <option value="go">go</option>
                  <option value="text">text</option>
                </select>
              </label>
            </div>
            <textarea
              className="modal-code"
              autoFocus
              spellCheck={false}
              placeholder="在这里粘贴题解或代码…"
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
            <div className="modal-foot">
              <span>{code ? `${code.split('\n').length} 行` : '支持直接 Ctrl+V 粘贴到画布'}</span>
              <div>
                <button className="tb-btn" onClick={() => setOpen(false)}>
                  取消
                </button>
                <button className="tb-primary" onClick={submit} disabled={!code.trim()}>
                  添加到画布
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
