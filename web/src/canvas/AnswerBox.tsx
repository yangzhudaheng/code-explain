import { useEffect, useRef } from 'react';
import type { Question } from '../model/types';
import { useCanvas } from '../store/canvasStore';
import { askAi, cancelAsk } from '../ai/ask';
import { useFlat } from './flat';
import { MarkdownLite } from './MarkdownLite';

export function AnswerBox({ q }: { q: Question }) {
  const ref = useRef<HTMLDivElement>(null);
  const flat = useFlat();
  const moveBox = useCanvas((s) => s.moveBox);
  const setBoxH = useCanvas((s) => s.setBoxH);
  const togglePin = useCanvas((s) => s.togglePin);
  const toggleCollapse = useCanvas((s) => s.toggleCollapse);
  const removeQuestion = useCanvas((s) => s.removeQuestion);
  const setSide = useCanvas((s) => s.setSide);
  const setHover = useCanvas((s) => s.setHover);
  const hoverQid = useCanvas((s) => s.hoverQid);

  useEffect(() => {
    const el = ref.current;
    // 导出视图里的副本绝不回写 boxH：它的宽度与正常视图一致但排版环境不同，
    // 回写会污染屏幕上的布局与连线端点
    if (!el || flat) return;
    const ro = new ResizeObserver(() => {
      setBoxH(q.id, el.offsetHeight);
    });
    ro.observe(el);
    setBoxH(q.id, el.offsetHeight);
    return () => ro.disconnect();
  }, [q.id, q.collapsed, flat, setBoxH]);

  const onHeadDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    if ((e.target as HTMLElement).closest('.box-btn')) return;
    e.stopPropagation();
    const el = e.currentTarget;
    const z = useCanvas.getState().docs[useCanvas.getState().activeId].viewport.zoom;
    let lx = e.clientX;
    let ly = e.clientY;
    el.setPointerCapture(e.pointerId);
    el.classList.add('dragging');

    const move = (ev: PointerEvent) => {
      moveBox(q.id, (ev.clientX - lx) / z, (ev.clientY - ly) / z);
      lx = ev.clientX;
      ly = ev.clientY;
    };
    const up = () => {
      el.classList.remove('dragging');
      el.removeEventListener('pointermove', move);
      el.removeEventListener('pointerup', up);
    };
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
  };

  const active = !flat && hoverQid === q.id;

  return (
    <div
      ref={ref}
      className={`answer ${active ? 'answer-active' : ''} ${q.collapsed ? 'is-collapsed' : ''}`}
      data-node="answer"
      style={{ left: q.boxX, top: q.boxY }}
      onMouseEnter={flat ? undefined : () => setHover(q.id)}
      onMouseLeave={flat ? undefined : () => setHover(null)}
    >
      <div className="answer-head" onPointerDown={onHeadDown}>
        <span className={`status-dot ${q.status}`} />
        <span className="answer-title" title={q.text}>
          {q.text}
        </span>
        <div className="answer-actions">
          {q.status !== 'streaming' && (
            <button
              className="box-btn"
              title={q.status === 'error' ? '重新回答' : '重新生成一次回答'}
              onClick={() => void askAi(q.id)}
            >
              ↻
            </button>
          )}
          <button
            className="box-btn"
            title={q.side === 'right' ? '移到代码左侧' : '移到代码右侧'}
            onClick={() => {
              setSide(q.id, q.side === 'right' ? 'left' : 'right');
              const rootEl = document.querySelector('.canvas-root') as HTMLElement | null;
              if (rootEl) {
                useCanvas.getState().ensureVisible(q.id, rootEl.getBoundingClientRect());
              }
            }}
          >
            {q.side === 'right' ? '←' : '→'}
          </button>
          <button
            className={`box-btn ${q.pinned ? 'on' : ''}`}
            title={q.pinned ? '已锁定位置，整理时不动' : '锁定位置'}
            onClick={() => togglePin(q.id)}
          >
            {q.pinned ? '锁' : '锁'}
          </button>
          <button className="box-btn" title="折叠/展开" onClick={() => toggleCollapse(q.id)}>
            {q.collapsed ? '＋' : '－'}
          </button>
          <button
            className="box-btn danger"
            title="删除"
            onClick={() => {
              // 先断流再删：否则后端还在生成，前端却已经找不到这条问答
              cancelAsk(q.id);
              removeQuestion(q.id);
            }}
          >
            ✕
          </button>
        </div>
      </div>

      {!q.collapsed && (
        <div className="answer-body">
          {q.answer ? (
            <>
              <MarkdownLite text={q.answer} />
              {q.status === 'streaming' && <span className="caret" />}
            </>
          ) : q.status === 'error' ? (
            <div className="answer-empty">生成失败，点标题栏的 ↻ 重试</div>
          ) : (
            <div className="answer-skeleton">
              <span />
              <span />
              <span />
            </div>
          )}
          <div className="answer-foot">
            <span>
              {q.side === 'right' ? '右侧' : '左侧'} · {q.model}
            </span>
            {q.stale ? (
              <span className="stale-tag" title="代码已修改，原文片段找不到了，高亮与箭头位置可能失准">
                锚点失效
              </span>
            ) : (
              <span>
                锚点 L{q.anchor.startLine + 1}:{q.anchor.startChar + 1}
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
