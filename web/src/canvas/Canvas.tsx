import { useEffect, useRef, useState } from 'react';
import { useCanvas } from '../store/canvasStore';
import { CodeBlock } from './CodeBlock';
import { AnswerBox } from './AnswerBox';
import { ConnectorLayer } from './ConnectorLayer';
import { SelectionPlus } from './SelectionPlus';
import { bgStyle, toneOf } from './background';
import { Toolbar } from '../ui/Toolbar';
import { Sidebar } from '../ui/Sidebar';
import { rememberActiveCanvas } from './workspace';

export function Canvas() {
  const rootRef = useRef<HTMLDivElement>(null);
  const doc = useCanvas((s) => s.docs[s.activeId]);
  const activeId = useCanvas((s) => s.activeId);
  const zoomAt = useCanvas((s) => s.zoomAt);
  const panBy = useCanvas((s) => s.panBy);
  const spaceRef = useRef(false);
  const [panning, setPanning] = useState(false);

  // 记住最后打开的画布：刷新后直接回到它，而不是永远跳回第一个
  useEffect(() => {
    rememberActiveCanvas(activeId);
  }, [activeId]);

  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      const inScroller = !!(e.target as HTMLElement | null)?.closest?.(
        '.code-scroll, .code-edit, .answer-body',
      );
      // 代码区/回答区/编辑框自己可滚动时，滚轮应该滚内容，而不是平移画布
      if (inScroller && !e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      if (e.ctrlKey || e.metaKey) {
        zoomAt(Math.exp(-e.deltaY * 0.0025), e.clientX, e.clientY, rect);
      } else {
        panBy(-e.deltaX, -e.deltaY);
      }
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [zoomAt, panBy]);

  useEffect(() => {
    const isField = (t: EventTarget | null) =>
      !!(t as HTMLElement | null)?.closest?.('input, textarea');
    const down = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !isField(e.target)) {
        e.preventDefault();
        spaceRef.current = true;
        setPanning(true);
      }
    };
    const up = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        spaceRef.current = false;
        setPanning(false);
      }
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
    };
  }, []);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const target = e.target as HTMLElement;
    // 浮层组件（加号、提问框、工具栏、侧栏、弹窗）不参与画布平移
    if (target.closest('.plus-btn, .plus-pop, .toolbar, .sidebar, .modal-mask')) return;
    const onNode = !!target.closest('[data-node]');
    const wantsPan = e.button === 1 || spaceRef.current || !onNode;
    if (!wantsPan) return;

    if (!onNode) window.getSelection()?.removeAllRanges();

    e.preventDefault();
    const el = rootRef.current;
    if (!el) return;
    el.setPointerCapture(e.pointerId);
    setPanning(true);
    let lx = e.clientX;
    let ly = e.clientY;

    const move = (ev: PointerEvent) => {
      panBy(ev.clientX - lx, ev.clientY - ly);
      lx = ev.clientX;
      ly = ev.clientY;
    };
    const up = () => {
      setPanning(false);
      el.removeEventListener('pointermove', move);
      el.removeEventListener('pointerup', up);
      if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
    };
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
  };

  // 兜底：任何时刻 docs 为空都不该白屏，给个空白画布
  if (!doc) {
    return <div ref={rootRef} className="canvas-root" />;
  }

  const { viewport, bg } = doc;
  const tone = toneOf(bg.tone);

  return (
    <div
      ref={rootRef}
      className={`canvas-root ${panning ? 'panning' : ''} tone-${bg.tone}`}
      style={{ background: tone.base }}
      onPointerDown={onPointerDown}
    >
      <div className="grid" style={bgStyle(bg, viewport)} />

      <div
        className="world"
        style={{
          transform: `translate(${viewport.x}px, ${viewport.y}px) scale(${viewport.zoom})`,
        }}
      >
        <ConnectorLayer />
        {doc.blocks.map((block) => (
          <CodeBlock
            key={block.id}
            block={block}
            questions={doc.questions.filter((q) => q.blockId === block.id)}
          />
        ))}
        {doc.questions.map((q) => (
          <AnswerBox key={q.id} q={q} />
        ))}
      </div>

      <Sidebar />
      <Toolbar />
      <SelectionPlus />
    </div>
  );
}
