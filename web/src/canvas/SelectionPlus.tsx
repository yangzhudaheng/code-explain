import { useEffect, useState } from 'react';
import type { Anchor, Rect4, Side } from '../model/types';
import { useCanvas } from '../store/canvasStore';
import { buildAnchor, measureAnchor, rangeToOffsets, trimOffsets } from './anchors';
import { chooseSide } from './layout';
import { startMockStream } from '../api/mockAi';

type SideMode = 'auto' | 'left' | 'right';

interface Pending {
  blockId: string;
  anchor: Anchor;
  anchorPoint: { x: number; y: number };
  anchorRects: Rect4[];
  screen: { x: number; y: number };
  /** 自动模式下算出来的落点，用于在弹层里提前告知 */
  autoSide: Side;
}

export function SelectionPlus() {
  const [pending, setPending] = useState<Pending | null>(null);
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [sideMode, setSideMode] = useState<SideMode>('auto');
  const addQuestion = useCanvas((s) => s.addQuestion);

  useEffect(() => {
    const onUp = (e: MouseEvent) => {
      const target = e.target as HTMLElement | null;
      if (target?.closest('.plus-pop') || target?.closest('.plus-btn')) return;

      const sel = window.getSelection();
      if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
        setPending(null);
        setOpen(false);
        return;
      }

      const range = sel.getRangeAt(0);
      const startEl = (
        range.startContainer.nodeType === Node.TEXT_NODE
          ? range.startContainer.parentElement
          : (range.startContainer as HTMLElement)
      ) as HTMLElement | null;
      const endEl = (
        range.endContainer.nodeType === Node.TEXT_NODE
          ? range.endContainer.parentElement
          : (range.endContainer as HTMLElement)
      ) as HTMLElement | null;

      const blockEl = startEl?.closest('[data-block-id]') as HTMLElement | null;
      if (!blockEl) {
        setPending(null);
        return;
      }
      // 跨块选择不允许（一个锚点只能属于一个代码块）
      if (!endEl || endEl.closest('[data-block-id]') !== blockEl) {
        setPending(null);
        return;
      }

      const pre = blockEl.querySelector('pre') as HTMLElement | null;
      const bodyEl = blockEl.querySelector('.block-body') as HTMLElement | null;
      if (!pre || !bodyEl) return;
      if (!pre.contains(range.startContainer) || !pre.contains(range.endContainer)) {
        setPending(null);
        return;
      }

      const state = useCanvas.getState();
      const doc = state.docs[state.activeId];
      const block = doc.blocks.find((b) => b.id === blockEl.dataset.blockId);
      if (!block) return;

      const raw = rangeToOffsets(pre, range);
      const { start, end } = trimOffsets(block.content, raw.start, raw.end);
      if (end - start < 1) {
        setPending(null);
        return;
      }

      const measured = measureAnchor({
        pre,
        blockEl,
        bodyEl,
        block,
        zoom: doc.viewport.zoom,
        startOffset: start,
        endOffset: end,
      });
      if (!measured) return;

      const screenRect = range.getBoundingClientRect();
      // 贴在选区末行右侧、垂直居中，避免压住下一行代码
      const lastRect =
        Array.from(range.getClientRects()).filter((r) => r.height > 0).pop() ?? screenRect;
      const btnX = Math.min(lastRect.right + 10, window.innerWidth - 40);
      const btnY = Math.max(16, Math.min(lastRect.top + lastRect.height / 2, window.innerHeight - 40));

      const rootEl = document.querySelector('.canvas-root') as HTMLElement | null;
      const autoSide = chooseSide({
        block,
        viewport: doc.viewport,
        containerRect: rootEl?.getBoundingClientRect() ?? new DOMRect(),
        anchorScreenX: lastRect.right,
      });

      setPending({
        blockId: block.id,
        anchor: buildAnchor(block.content, start, end),
        anchorPoint: measured.point,
        anchorRects: measured.rects,
        screen: { x: btnX, y: btnY },
        autoSide,
      });
      setOpen(false);
    };

    document.addEventListener('mouseup', onUp);
    return () => document.removeEventListener('mouseup', onUp);
  }, []);

  const submit = () => {
    if (!pending) return;
    const question = text.trim();
    if (!question) return;
    const side: Side = sideMode === 'auto' ? pending.autoSide : sideMode;
    const id = addQuestion({
      blockId: pending.blockId,
      anchor: pending.anchor,
      anchorPoint: pending.anchorPoint,
      anchorRects: pending.anchorRects,
      text: question,
      side,
      sideMode: sideMode === 'auto' ? 'auto' : 'manual',
    });
    // 手动选左侧且代码块贴着画布左缘时，回答框会落到可视区外，这里平移画布把它拉回来
    const rootEl = document.querySelector('.canvas-root') as HTMLElement | null;
    if (rootEl) useCanvas.getState().ensureVisible(id, rootEl.getBoundingClientRect());
    startMockStream(id, question, pending.anchor.quote);
    window.getSelection()?.removeAllRanges();
    setPending(null);
    setOpen(false);
    setText('');
  };

  if (!pending) return null;

  const popX = Math.min(pending.screen.x - 8, window.innerWidth - 320);
  const popY = Math.min(pending.screen.y + 34, window.innerHeight - 250);

  const SIDE_OPTIONS: { id: SideMode; label: string }[] = [
    { id: 'auto', label: `自动 · ${pending.autoSide === 'left' ? '左' : '右'}` },
    { id: 'left', label: '左侧' },
    { id: 'right', label: '右侧' },
  ];

  return (
    <>
      {!open && (
        <button
          className="plus-btn"
          style={{ left: pending.screen.x, top: pending.screen.y }}
          title="针对选中的代码提问"
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => setOpen(true)}
        >
          +
        </button>
      )}

      {open && (
        <div className="plus-pop" style={{ left: popX, top: popY }}>
          <div className="pop-quote">
            <span className="pop-quote-tag">已选中</span>
            {pending.anchor.quote.replace(/\s+/g, ' ').slice(0, 90)}
          </div>
          <div className="pop-side">
            <span className="pop-side-label">回答位置</span>
            <div className="seg">
              {SIDE_OPTIONS.map((opt) => (
                <button
                  key={opt.id}
                  className={sideMode === opt.id ? 'on' : ''}
                  onClick={() => setSideMode(opt.id)}
                  onMouseDown={(e) => e.preventDefault()}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>
          <textarea
            autoFocus
            rows={3}
            value={text}
            placeholder="针对这段代码提问，例如：这里为什么用 lo < hi？"
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
              if (e.key === 'Escape') {
                setOpen(false);
                setPending(null);
              }
            }}
          />
          <div className="pop-foot">
            <span>Enter 提交 · Shift+Enter 换行</span>
            <button onClick={submit} disabled={!text.trim()}>
              提问
            </button>
          </div>
        </div>
      )}
    </>
  );
}
