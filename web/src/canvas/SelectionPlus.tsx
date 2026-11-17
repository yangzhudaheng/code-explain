import { useEffect, useState } from 'react';
import type { Anchor, Rect4 } from '../model/types';
import { useCanvas } from '../store/canvasStore';
import { buildAnchor, measureAnchor, rangeToOffsets, trimOffsets } from './anchors';
import { startMockStream } from '../api/mockAi';

interface Pending {
  blockId: string;
  anchor: Anchor;
  anchorPoint: { x: number; y: number };
  anchorRects: Rect4[];
  screen: { x: number; y: number };
}

export function SelectionPlus() {
  const [pending, setPending] = useState<Pending | null>(null);
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
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

      setPending({
        blockId: block.id,
        anchor: buildAnchor(block.content, start, end),
        anchorPoint: measured.point,
        anchorRects: measured.rects,
        screen: { x: btnX, y: btnY },
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
    const id = addQuestion({
      blockId: pending.blockId,
      anchor: pending.anchor,
      anchorPoint: pending.anchorPoint,
      anchorRects: pending.anchorRects,
      text: question,
    });
    startMockStream(id, question, pending.anchor.quote);
    window.getSelection()?.removeAllRanges();
    setPending(null);
    setOpen(false);
    setText('');
  };

  if (!pending) return null;

  const popX = Math.min(pending.screen.x - 8, window.innerWidth - 320);
  const popY = Math.min(pending.screen.y + 34, window.innerHeight - 220);

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
