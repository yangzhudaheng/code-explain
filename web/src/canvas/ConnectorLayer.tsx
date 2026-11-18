import { memo } from 'react';
import { bezierPoint, BOX_WIDTH, connectorOpacity, DEFAULT_BOX_H } from './layout';
import { useFlat } from './flat';
import { useInteracting } from './interaction';
import { useCanvas } from '../store/canvasStore';
import type { CodeBlockModel, Question, Side } from '../model/types';

interface Geometry {
  start: { x: number; y: number };
  end: { x: number; y: number };
  p1: { x: number; y: number };
  p2: { x: number; y: number };
  path: string;
  gapX: number;
}

/** 按回答框所在侧计算连线：右侧从代码块右边界出发，左侧从左边界出发 */
function geometryOf(q: Question, block: CodeBlockModel | undefined, fan: number): Geometry {
  const side: Side = q.side;
  const startX = block ? (side === 'right' ? block.x + block.width : block.x) : q.anchorPoint.x;
  const start = { x: startX, y: q.anchorPoint.y + fan };
  const endX = side === 'right' ? q.boxX : q.boxX + BOX_WIDTH;
  const end = { x: endX, y: q.boxY + (q.boxH || DEFAULT_BOX_H) / 2 };

  const dx = Math.max(40, Math.abs(end.x - start.x) * 0.4);
  // 从代码块指向回答框的方向：右列 +1，左列 -1
  const out = side === 'right' ? 1 : -1;
  const p1 = { x: end.x - out * dx, y: end.y };
  const p2 = { x: start.x + out * dx, y: start.y };
  const path = `M ${end.x} ${end.y} C ${p1.x} ${p1.y}, ${p2.x} ${p2.y}, ${start.x} ${start.y}`;

  return { start, end, p1, p2, path, gapX: (start.x + end.x) / 2 };
}

function ConnectorLayerImpl() {
  const questions = useCanvas((s) => s.docs[s.activeId].questions);
  const blocks = useCanvas((s) => s.docs[s.activeId].blocks);
  const hoverQid = useCanvas((s) => s.hoverQid);
  const setHover = useCanvas((s) => s.setHover);
  const zoom = useCanvas((s) => s.docs[s.activeId].viewport.zoom);
  const flat = useFlat();
  const busy = useInteracting();

  const counts = new Map<string, number>();
  for (const q of questions) {
    counts.set(q.blockId, (counts.get(q.blockId) ?? 0) + 1);
  }

  // 同一代码片段上的多个问题：起点按 ±12px 错开，避免连线完全重合
  const anchorGroups = new Map<string, string[]>();
  for (const q of questions) {
    const key = `${q.blockId}:${q.anchor.startOffset}:${q.anchor.endOffset}`;
    anchorGroups.set(key, [...(anchorGroups.get(key) ?? []), q.id]);
  }
  const fanOffset = (qid: string, key: string) => {
    const list = anchorGroups.get(key) ?? [qid];
    if (list.length < 2) return 0;
    const index = list.indexOf(qid);
    return (index - (list.length - 1) / 2) * 12;
  };

  const geom = new Map<string, Geometry>();
  for (const q of questions) {
    const block = blocks.find((b) => b.id === q.blockId);
    const fan = fanOffset(q.id, `${q.blockId}:${q.anchor.startOffset}:${q.anchor.endOffset}`);
    geom.set(q.id, geometryOf(q, block, fan));
  }

  return (
    <>
      {/*
        屏幕上的 svg 是 1×1 + overflow visible 的技巧；导出时要按画布尺寸铺开，
        否则栅格化时 <foreignObject> 只保留 svg 自己的 viewport，连线全被裁掉。
      */}
      <svg
        className="connector-svg"
        width={flat ? flat.width : 1}
        height={flat ? flat.height : 1}
      >
        {questions.map((q) => {
          const g = geom.get(q.id)!;
          const base = connectorOpacity(counts.get(q.blockId) ?? 1);
          const active = !flat && hoverQid === q.id;
          const dimmed = !flat && hoverQid !== null && !active;
          const opacity = dimmed ? 0.12 : active ? 1 : base;

          return (
            <g key={q.id}>
              <path
                d={g.path}
                fill="none"
                stroke={active ? '#2f6fed' : '#9fb2d4'}
                strokeWidth={active ? 1.8 : 1.2}
                strokeDasharray={q.stale ? '4 4' : undefined}
                opacity={opacity}
                markerEnd="url(#arrow-head)"
              />
              {!flat && !busy && (
                <path
                  d={g.path}
                  className="connector-hit"
                  fill="none"
                  stroke="transparent"
                  strokeWidth={14}
                  onMouseEnter={() => setHover(q.id)}
                  onMouseLeave={() => setHover(null)}
                />
              )}
            </g>
          );
        })}
        <defs>
          <marker
            id="arrow-head"
            viewBox="0 0 10 10"
            refX="9"
            refY="5"
            markerWidth="6"
            markerHeight="6"
            orient="auto-start-reverse"
          >
            <path
              d="M 1 1 L 9 5 L 1 9"
              fill="none"
              stroke="context-stroke"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </marker>
        </defs>
      </svg>

      {questions.map((q) => {
        const g = geom.get(q.id)!;
        // 标签放在「代码块边界」与「回答框边界」之间的空隙里，
        // 而不是曲线固定 t=0.5 处，避免压到代码文字
        let labelPos = { x: g.gapX, y: (g.start.y + g.end.y) / 2 };
        let bestDist = Number.POSITIVE_INFINITY;
        for (let i = 1; i < 20; i++) {
          const p = bezierPoint(g.end, g.p1, g.p2, g.start, i / 20);
          const dist = Math.abs(p.x - g.gapX);
          if (dist < bestDist) {
            bestDist = dist;
            labelPos = { x: p.x, y: p.y };
          }
        }
        const active = !flat && hoverQid === q.id;
        const label = q.text.length > 12 ? `${q.text.slice(0, 12)}…` : q.text;

        return (
          <div
            key={`label_${q.id}`}
            className={`line-label ${active ? 'line-label-active' : ''}`}
            style={{
              left: labelPos.x,
              top: labelPos.y,
              // 标签逆缩放：无论画布放大缩小，问题文本始终保持可读字号
              transform: `translate(-50%, -50%) scale(${flat ? 1 : 1 / zoom})`,
            }}
            onMouseEnter={flat ? undefined : () => setHover(q.id)}
            onMouseLeave={flat ? undefined : () => setHover(null)}
            title={q.text}
          >
            {label}
          </div>
        );
      })}
    </>
  );
}

/**
 * 连线层自己订阅 store，不需要从 Canvas 拿 props。
 * 用 memo 把「父组件每帧重渲染」挡在外面：平移只改 .world 的 transform，
 * 连线在屏幕上的位置全靠这层变换，根本不必重算 150 条贝塞尔。
 */
export const ConnectorLayer = memo(ConnectorLayerImpl);
