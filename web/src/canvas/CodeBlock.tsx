import { useMemo } from 'react';
import type { CodeBlockModel, Question } from '../model/types';
import { useCanvas } from '../store/canvasStore';
import { tokenize } from './highlight';

interface Props {
  block: CodeBlockModel;
  questions: Question[];
}

export function CodeBlock({ block, questions }: Props) {
  const moveBlock = useCanvas((s) => s.moveBlock);
  const hoverQid = useCanvas((s) => s.hoverQid);
  const zoom = useCanvas((s) => s.docs[s.activeId].viewport.zoom);

  const onHeadDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    e.stopPropagation();
    const el = e.currentTarget;
    const z = useCanvas.getState().docs[useCanvas.getState().activeId].viewport.zoom;
    let lx = e.clientX;
    let ly = e.clientY;
    el.setPointerCapture(e.pointerId);
    el.classList.add('dragging');

    const move = (ev: PointerEvent) => {
      moveBlock(block.id, (ev.clientX - lx) / z, (ev.clientY - ly) / z);
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

  const lines = useMemo(() => block.content.split('\n'), [block.content]);
  const tokens = useMemo(
    () => tokenize(block.content, block.language),
    [block.content, block.language],
  );

  return (
    <div
      className="block"
      data-node="block"
      data-block-id={block.id}
      style={{
        left: block.x,
        top: block.y,
        width: block.width,
        // 缩放时保持 1px 边框视觉宽度，避免放大后粗得难看
        borderWidth: 1 / zoom,
      }}
    >
      <div className="block-head" onPointerDown={onHeadDown}>
        <span className="chip">{block.language}</span>
        <span className="block-title">{block.title}</span>
        <span className="block-meta">
          {lines.length} 行 · {questions.length > 0 ? `${questions.length} 个提问` : '可选中提问'}
        </span>
      </div>
      <div className="block-body">
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
                  borderWidth: 2 / zoom,
                }}
              />
            )),
          )}
        </div>
      </div>
    </div>
  );
}
