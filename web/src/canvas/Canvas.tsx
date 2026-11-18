import { useEffect, useMemo, useRef, useState } from 'react';
import { useCanvas } from '../store/canvasStore';
import type { Question } from '../model/types';
import { CodeBlock } from './CodeBlock';
import { AnswerBox } from './AnswerBox';
import { ConnectorLayer } from './ConnectorLayer';
import { SelectionPlus } from './SelectionPlus';
import { bgStyle, toneOf } from './background';
import { Toolbar } from '../ui/Toolbar';
import { Sidebar } from '../ui/Sidebar';
import { rememberActiveCanvas } from './workspace';
import { answerVisible, blockVisible, visibleWorldRect } from './cull';
import {
  beginInteraction,
  createFrameCoalescer,
  endInteraction,
  pulseInteraction,
  useInteracting,
} from './interaction';

/** 稳定的空数组：不要每次渲染都 `[]`，否则子组件的 memo 比较永远不相等 */
const NO_QUESTIONS: Question[] = [];

export function Canvas() {
  const rootRef = useRef<HTMLDivElement>(null);
  const doc = useCanvas((s) => s.docs[s.activeId]);
  const activeId = useCanvas((s) => s.activeId);
  const zoomAt = useCanvas((s) => s.zoomAt);
  const panBy = useCanvas((s) => s.panBy);
  const spaceRef = useRef(false);
  const [panning, setPanning] = useState(false);
  const busy = useInteracting();
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);

  const hasDoc = !!doc;
  const viewport = doc?.viewport;

  // 视口裁剪需要容器尺寸：世界坐标 ↔ 屏幕坐标的换算离不开它
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const read = () => setSize({ w: el.clientWidth, h: el.clientHeight });
    read();
    const ro = new ResizeObserver(read);
    ro.observe(el);
    return () => ro.disconnect();
  }, [hasDoc]);

  // 记住最后打开的画布：刷新后直接回到它，而不是永远跳回第一个
  useEffect(() => {
    rememberActiveCanvas(activeId);
  }, [activeId]);

  const view = useMemo(
    () => (size && viewport ? visibleWorldRect(size, viewport) : null),
    [size, viewport],
  );

  /** 代码块 → 它的提问。每帧都重建 Map 也只是一次线性扫描，比每个块 filter 一遍便宜得多 */
  const questionsByBlock = useMemo(() => {
    const map = new Map<string, Question[]>();
    for (const q of doc?.questions ?? NO_QUESTIONS) {
      const list = map.get(q.blockId);
      if (list) list.push(q);
      else map.set(q.blockId, [q]);
    }
    return map;
  }, [doc?.questions]);

  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    // 滚轮事件比屏幕刷新快，合并到每帧一次，避免一帧内多次重渲染
    const wheelPan = createFrameCoalescer((dx, dy) => panBy(dx, dy));
    const onWheel = (e: WheelEvent) => {
      const inScroller = !!(e.target as HTMLElement | null)?.closest?.(
        '.code-scroll, .code-edit, .answer-body',
      );
      // 代码区/回答区/编辑框自己可滚动时，滚轮应该滚内容，而不是平移画布
      if (inScroller && !e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      const rect = el.getBoundingClientRect();
      if (e.ctrlKey || e.metaKey) {
        // 缩放没有「结束」事件，用尾部超时退出交互态
        pulseInteraction();
        zoomAt(Math.exp(-e.deltaY * 0.0025), e.clientX, e.clientY, rect);
      } else {
        pulseInteraction(140);
        wheelPan.add(-e.deltaX, -e.deltaY);
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
    beginInteraction();
    let lx = e.clientX;
    let ly = e.clientY;
    const drag = createFrameCoalescer((dx, dy) => panBy(dx, dy));

    const move = (ev: PointerEvent) => {
      drag.add(ev.clientX - lx, ev.clientY - ly);
      lx = ev.clientX;
      ly = ev.clientY;
    };
    const up = () => {
      drag.flush();
      setPanning(false);
      endInteraction();
      el.removeEventListener('pointermove', move);
      el.removeEventListener('pointerup', up);
      el.removeEventListener('pointercancel', up);
      if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
    };
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
  };

  // 兜底：任何时刻 docs 为空都不该白屏，给个空白画布
  if (!doc) {
    return <div ref={rootRef} className="canvas-root" />;
  }

  const { bg } = doc;
  const tone = toneOf(bg.tone);

  return (
    <div
      ref={rootRef}
      className={`canvas-root ${panning ? 'panning' : ''} tone-${bg.tone}`}
      style={{ background: tone.base }}
      onPointerDown={onPointerDown}
    >
      <div className="grid" style={bgStyle(bg, viewport!)} />

      <div
        className={`world ${busy ? 'is-busy' : ''}`}
        style={{
          transform: `translate(${viewport!.x}px, ${viewport!.y}px) scale(${viewport!.zoom})`,
        }}
      >
        <ConnectorLayer />
        {doc.blocks.map((block) => (
          <CodeBlock
            key={block.id}
            block={block}
            questions={questionsByBlock.get(block.id) ?? NO_QUESTIONS}
            visible={view ? blockVisible(block, view) : true}
          />
        ))}
        {doc.questions.map((q) => (
          <AnswerBox key={q.id} q={q} visible={view ? answerVisible(q, view) : true} />
        ))}
      </div>

      <Sidebar />
      <Toolbar />
      <SelectionPlus />
    </div>
  );
}
