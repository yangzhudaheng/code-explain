import { bezierPoint, connectorOpacity, DEFAULT_BOX_H } from './layout';
import { useCanvas } from '../store/canvasStore';

export function ConnectorLayer() {
  const questions = useCanvas((s) => s.docs[s.activeId].questions);
  const blocks = useCanvas((s) => s.docs[s.activeId].blocks);
  const hoverQid = useCanvas((s) => s.hoverQid);
  const setHover = useCanvas((s) => s.setHover);
  const zoom = useCanvas((s) => s.docs[s.activeId].viewport.zoom);

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

  return (
    <>
      <svg className="connector-svg" width="1" height="1">
        {questions.map((q) => {
          const start = q.anchorPoint;
          const dy = fanOffset(q.id, `${q.blockId}:${q.anchor.startOffset}:${q.anchor.endOffset}`);
          const from = { x: start.x, y: start.y + dy };
          const end = { x: q.boxX, y: q.boxY + (q.boxH || DEFAULT_BOX_H) / 2 };
          const dx = Math.max(40, Math.abs(end.x - from.x) * 0.4);
          const d = `M ${end.x} ${end.y} C ${end.x - dx} ${end.y}, ${from.x + dx} ${from.y}, ${from.x} ${from.y}`;
          const base = connectorOpacity(counts.get(q.blockId) ?? 1);
          const active = hoverQid === q.id;
          const dimmed = hoverQid !== null && !active;
          const opacity = dimmed ? 0.12 : active ? 1 : base;

          return (
            <g key={q.id}>
              <path
                d={d}
                fill="none"
                stroke={active ? '#2f6fed' : '#9fb2d4'}
                strokeWidth={active ? 1.8 : 1.2}
                opacity={opacity}
                markerEnd="url(#arrow-head)"
              />
              <path
                d={d}
                className="connector-hit"
                fill="none"
                stroke="transparent"
                strokeWidth={14}
                onMouseEnter={() => setHover(q.id)}
                onMouseLeave={() => setHover(null)}
              />
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
        const start = q.anchorPoint;
        const end = { x: q.boxX, y: q.boxY + (q.boxH || DEFAULT_BOX_H) / 2 };
        const dx = Math.max(40, Math.abs(end.x - start.x) * 0.4);
        const p0 = end;
        const p1 = { x: end.x - dx, y: end.y };
        const p2 = { x: start.x + dx, y: start.y };
        const block = blocks.find((b) => b.id === q.blockId);
        // 标签放在「代码块右边界」与「回答框左边界」之间的空隙里，
        // 而不是曲线固定 t=0.5 处，避免压到代码文字
        const gapX = block
          ? (block.x + block.width + q.boxX) / 2
          : (start.x + end.x) / 2;
        let labelPos = { x: gapX, y: (start.y + end.y) / 2 };
        let bestDist = Number.POSITIVE_INFINITY;
        for (let i = 1; i < 20; i++) {
          const t = i / 20;
          const p = bezierPoint(p0, p1, p2, start, t);
          const dist = Math.abs(p.x - gapX);
          if (dist < bestDist) {
            bestDist = dist;
            labelPos = { x: p.x, y: p.y };
          }
        }
        const active = hoverQid === q.id;
        const label = q.text.length > 12 ? `${q.text.slice(0, 12)}…` : q.text;

        return (
          <div
            key={`label_${q.id}`}
            className={`line-label ${active ? 'line-label-active' : ''}`}
            style={{
              left: labelPos.x,
              top: labelPos.y,
              // 标签逆缩放：无论画布放大缩小，问题文本始终保持可读字号
              transform: `translate(-50%, -50%) scale(${1 / zoom})`,
            }}
            onMouseEnter={() => setHover(q.id)}
            onMouseLeave={() => setHover(null)}
            title={q.text}
          >
            {label}
          </div>
        );
      })}
    </>
  );
}
