import { memo, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CodeBlockModel, Question, Rect4 } from '../model/types';
import { useCanvas } from '../store/canvasStore';
import { measureAnchor } from './anchors';
import { useFlat } from './flat';
import { beginInteraction, createFrameCoalescer, endInteraction } from './interaction';
import { tokenize } from './highlight';

interface Props {
  block: CodeBlockModel;
  questions: Question[];
  /** 视口裁剪：完全在视野外的代码块不挂载 */
  visible: boolean;
}

const LANGS = [
  { id: 'java', label: 'Java' },
  { id: 'cpp', label: 'C++' },
  { id: 'python', label: 'Python' },
  { id: 'javascript', label: 'JavaScript' },
  { id: 'go', label: 'Go' },
  { id: 'text', label: '纯文本' },
];

/** 粘贴进来的代码常带 \r\n 与尾部空行，存库前统一掉，避免行号多一行空白 */
function normalizeCode(src: string) {
  return src.replace(/\r\n?/g, '\n').replace(/^\n+/, '').replace(/\s+$/, '');
}

function CodeBlockImpl({ block, questions, visible }: Props) {
  const moveBlock = useCanvas((s) => s.moveBlock);
  const updateBlock = useCanvas((s) => s.updateBlock);
  const removeBlock = useCanvas((s) => s.removeBlock);
  const refreshAnchors = useCanvas((s) => s.refreshAnchors);
  // 只关心「悬停的框是不是本块里的」，别的块的 hover 不该让这里重渲染
  const hoverQid = useCanvas((s) =>
    s.hoverQid && questions.some((q) => q.id === s.hoverQid) ? s.hoverQid : null,
  );
  const zoom = useCanvas((s) => s.docs[s.activeId].viewport.zoom);
  const flat = useFlat();
  /** 画布缩小时把边框加粗到 1/zoom，保证视觉上始终是 1px；导出视图恒为 1 */
  const unit = flat ? 1 : 1 / zoom;

  const rootRef = useRef<HTMLDivElement>(null);
  const gutterRef = useRef<HTMLDivElement>(null);

  const [editing, setEditing] = useState(false);
  const [confirmDel, setConfirmDel] = useState(false);
  const [draft, setDraft] = useState('');
  const [title, setTitle] = useState('');
  const [lang, setLang] = useState('java');

  const onHeadDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    // 标题栏上的按钮、输入框不参与拖动
    if ((e.target as HTMLElement).closest('button, input, select, .del-confirm')) return;
    e.stopPropagation();
    const el = e.currentTarget;
    const z = useCanvas.getState().docs[useCanvas.getState().activeId].viewport.zoom;
    let lx = e.clientX;
    let ly = e.clientY;
    el.setPointerCapture(e.pointerId);
    el.classList.add('dragging');
    beginInteraction();

    const drag = createFrameCoalescer((dx, dy) => moveBlock(block.id, dx / z, dy / z));
    const move = (ev: PointerEvent) => {
      drag.add(ev.clientX - lx, ev.clientY - ly);
      lx = ev.clientX;
      ly = ev.clientY;
    };
    const up = () => {
      drag.flush();
      el.classList.remove('dragging');
      endInteraction();
      el.removeEventListener('pointermove', move);
      el.removeEventListener('pointerup', up);
      el.removeEventListener('pointercancel', up);
    };
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
  };

  const beginEdit = () => {
    setDraft(block.content);
    setTitle(block.title);
    setLang(block.language);
    setConfirmDel(false);
    setEditing(true);
  };

  const commit = () => {
    updateBlock(block.id, {
      content: normalizeCode(draft),
      title: title.trim() || 'snippet.txt',
      language: lang,
    });
    setEditing(false);
  };

  const lines = useMemo(() => block.content.split('\n'), [block.content]);
  const tokens = useMemo(
    () => tokenize(block.content, block.language),
    [block.content, block.language],
  );
  const draftLines = useMemo(() => (editing ? draft.split('\n') : []), [editing, draft]);

  /**
   * 文本被改过之后，锚点的行内矩形与连线起点必须重测，否则高亮和箭头会错行。
   * 只在偏移/内容变化时跑，且 refreshAnchors 内部做了等值判断，不会形成循环。
   */
  const sig = questions
    .map((q) => `${q.id}:${q.anchor.startOffset}:${q.anchor.endOffset}:${q.stale ? 1 : 0}`)
    .join('|');

  // 拖动时 block 对象每帧都变，但几何只需在内容/偏移变化时重测，
  // 因此用 ref 读最新值，避免把 block 放进依赖里每帧测量
  const blockRef = useRef(block);
  blockRef.current = block;

  useLayoutEffect(() => {
    // 导出视图里的这层副本不该反过来改 store：锚点几何只由屏幕上的那份负责
    if (editing || flat) return;
    const blockEl = rootRef.current;
    const pre = blockEl?.querySelector('pre') as HTMLElement | null;
    const bodyEl = blockEl?.querySelector('.block-body') as HTMLElement | null;
    if (!blockEl || !pre || !bodyEl) return;

    const patch: Record<string, { rects: Rect4[]; point: { x: number; y: number } }> = {};
    for (const q of questions) {
      if (q.stale) continue;
      // 偏移与实际文本对不上，说明还没重定位（或代码又被改过），先跳过
      if (block.content.slice(q.anchor.startOffset, q.anchor.endOffset) !== q.anchor.quote) continue;
      const measured = measureAnchor({
        pre,
        blockEl,
        bodyEl,
        block: blockRef.current,
        zoom,
        startOffset: q.anchor.startOffset,
        endOffset: q.anchor.endOffset,
      });
      if (measured) patch[q.id] = measured;
    }

    if (Object.keys(patch).length === 0) return;
    refreshAnchors(block.id, patch);
  }, [block.id, block.content, block.language, editing, flat, sig, zoom, refreshAnchors, visible]);

  const onTextKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      setEditing(false);
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      commit();
      return;
    }
    if (e.key === 'Tab') {
      e.preventDefault();
      const el = e.currentTarget;
      const start = el.selectionStart;
      const end = el.selectionEnd;
      const next = `${el.value.slice(0, start)}    ${el.value.slice(end)}`;
      setDraft(next);
      requestAnimationFrame(() => {
        el.selectionStart = start + 4;
        el.selectionEnd = start + 4;
      });
    }
  };

  const editHeight = Math.min(520, Math.max(120, draftLines.length * 20 + 24));

  // 视口外的块不挂载：省下的是语法着色后上千个 <span> 的布局与栅格化
  if (!visible) return null;

  return (
    <div
      ref={rootRef}
      className={`block ${editing ? 'is-editing' : ''}`}
      data-node="block"
      data-block-id={block.id}
      style={{
        left: block.x,
        top: block.y,
        width: block.width,
        // 缩放时保持 1px 边框视觉宽度，避免放大后粗得难看
        borderWidth: unit,
      }}
    >
      <div className="block-head" onPointerDown={onHeadDown}>
        <span className="chip">{block.language}</span>
        <span className="block-title">{block.title}</span>

        {confirmDel ? (
          <div className="del-confirm">
            <span className="del-confirm-text">
              删除该块{questions.length ? `及 ${questions.length} 个提问` : ''}？
            </span>
            <button className="block-btn danger" onClick={() => removeBlock(block.id)}>
              删除
            </button>
            <button className="block-btn" onClick={() => setConfirmDel(false)}>
              取消
            </button>
          </div>
        ) : (
          <>
            <span className="block-meta">
              {editing
                ? '编辑中 · Ctrl+Enter 保存'
                : `${block.content ? `${lines.length} 行` : '空'} · ${questions.length > 0 ? `${questions.length} 个提问` : '可选中提问'}`}
            </span>
            <div className="block-actions">
              {!editing && (
                <button className="block-btn" title="编辑代码" onClick={beginEdit}>
                  编辑
                </button>
              )}
              <button
                className="block-btn danger"
                title="删除代码块"
                onClick={() => setConfirmDel(true)}
              >
                删除
              </button>
            </div>
          </>
        )}
      </div>

      {editing ? (
        <div className="block-body is-editing">
          <div className="edit-area" style={{ height: editHeight }}>
            <div className="gutter edit-gutter" ref={gutterRef} aria-hidden="true">
              {draftLines.map((_, i) => (
                <span className="ln" key={i}>
                  {i + 1}
                </span>
              ))}
            </div>
            <textarea
              className="code-edit"
              value={draft}
              wrap="off"
              spellCheck={false}
              autoFocus
              placeholder="粘贴或输入代码…"
              onChange={(e) => setDraft(e.target.value)}
              onScroll={(e) => {
                if (gutterRef.current) gutterRef.current.scrollTop = e.currentTarget.scrollTop;
              }}
              onKeyDown={onTextKeyDown}
            />
          </div>
          <div className="edit-bar">
            <input
              className="edit-title"
              value={title}
              placeholder="文件名"
              onChange={(e) => setTitle(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commit();
                if (e.key === 'Escape') setEditing(false);
              }}
            />
            <select
              className="edit-lang"
              value={LANGS.some((l) => l.id === lang) ? lang : 'text'}
              onChange={(e) => setLang(e.target.value)}
            >
              {LANGS.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.label}
                </option>
              ))}
            </select>
            <button className="edit-btn ghost" onClick={() => setDraft('')}>
              清空
            </button>
            <button className="edit-btn" onClick={() => setEditing(false)}>
              取消
            </button>
            <button className="edit-btn primary" onClick={commit}>
              保存
            </button>
          </div>
        </div>
      ) : (
        <div className="block-body">
          {block.content ? (
            <div className="code-scroll">
              <div className="gutter" aria-hidden="true">
                {lines.map((_, i) => (
                  <span className="ln" key={i}>
                    {i + 1}
                  </span>
                ))}
              </div>
              <pre className="code" data-pre-id={block.id}>
                {tokens.map((t, i) =>
                  t.cls ? (
                    <span key={i} className={`tok-${t.cls}`}>
                      {t.text}
                    </span>
                  ) : (
                    t.text
                  ),
                )}
              </pre>
            </div>
          ) : (
            <div className="code-empty">空代码块 · 点右上角「编辑」粘贴代码</div>
          )}
          <div className="hl-layer">
            {questions.map((q) =>
              q.anchorRects.map((r, i) => (
                <div
                  key={`${q.id}_${i}`}
                  className={`hl ${i === 0 ? 'hl-start' : ''} ${hoverQid === q.id ? 'hl-active' : ''}`}
                  style={{
                    left: r.left,
                    top: r.top,
                    width: r.width,
                    height: r.height,
                    borderWidth: 2 * unit,
                  }}
                />
              )),
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 拖动/平移时 Canvas 每帧都会整棵重渲染，块自身没变化就不该跟着重画。
 * questions 用「逐个引用比较」而不是数组引用：Canvas 每次渲染都会塞一个新数组进来，
 * 但 arrange 保证没被移动的问答对象引用不变，所以逐项比较能真正跳过无关的块。
 */
export const CodeBlock = memo(CodeBlockImpl, (a, b) => {
  if (a.block !== b.block || a.visible !== b.visible) return false;
  if (a.questions === b.questions) return true;
  if (a.questions.length !== b.questions.length) return false;
  return a.questions.every((q, i) => q === b.questions[i]);
});
