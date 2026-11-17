import type { CanvasDoc, CodeBlockModel, Question, Viewport } from '../model/types';

export const COL_OFFSET = 140;
export const BOX_GAP = 16;
export const BOX_WIDTH = 320;
export const DEFAULT_BOX_H = 110;

export function clamp(v: number, min: number, max: number) {
  return Math.min(max, Math.max(min, v));
}

/** 屏幕坐标 → 世界坐标 */
export function screenToWorld(
  sx: number,
  sy: number,
  viewport: Viewport,
  containerRect: DOMRect,
) {
  return {
    x: (sx - containerRect.left - viewport.x) / viewport.zoom,
    y: (sy - containerRect.top - viewport.y) / viewport.zoom,
  };
}

/** 以某个屏幕点为锚点缩放，返回新的 viewport */
export function zoomAround(
  viewport: Viewport,
  factor: number,
  sx: number,
  sy: number,
  containerRect: DOMRect,
): Viewport {
  const zoom = clamp(viewport.zoom * factor, 0.25, 2.5);
  const world = screenToWorld(sx, sy, viewport, containerRect);
  return {
    zoom,
    x: sx - containerRect.left - world.x * zoom,
    y: sy - containerRect.top - world.y * zoom,
  };
}

/**
 * 单侧布局 + 贪心避让。
 * 规则：
 * - 回答框固定在「代码块右边界 + 96」的列上
 * - 按锚点 y 排序后依次下推，间距 16
 * - pinned 的框完全不参与重排（用户的意图优先）
 */
export function arrange(questions: Question[], blocks: CodeBlockModel[]): Question[] {
  const next = questions.map((q) => ({ ...q }));
  const groups = new Map<string, Question[]>();
  for (const q of next) {
    const list = groups.get(q.blockId) ?? [];
    list.push(q);
    groups.set(q.blockId, list);
  }

  for (const [blockId, list] of groups) {
    const block = blocks.find((b) => b.id === blockId);
    const colX = block ? block.x + block.width + COL_OFFSET : COL_OFFSET;

    list.sort((a, b) => a.anchorPoint.y - b.anchorPoint.y);
    let cursor = Number.NEGATIVE_INFINITY;

    for (const q of list) {
      if (q.pinned) {
        cursor = Math.max(cursor, q.boxY + (q.boxH || DEFAULT_BOX_H) + BOX_GAP);
        continue;
      }
      const desired = q.anchorPoint.y - 24;
      const y =
        cursor === Number.NEGATIVE_INFINITY ? desired : Math.max(desired, cursor);
      q.boxX = colX;
      q.boxY = y;
      cursor = y + (q.boxH || DEFAULT_BOX_H) + BOX_GAP;
    }
  }

  return next;
}

/** 三点贝塞尔上的取点，用于给问题标签找位置 */
export function bezierPoint(
  p0: { x: number; y: number },
  p1: { x: number; y: number },
  p2: { x: number; y: number },
  p3: { x: number; y: number },
  t: number,
) {
  const mt = 1 - t;
  const a = mt * mt * mt;
  const b = 3 * mt * mt * t;
  const c = 3 * mt * t * t;
  const d = t * t * t;
  return {
    x: a * p0.x + b * p1.x + c * p2.x + d * p3.x,
    y: a * p0.y + b * p1.y + c * p2.y + d * p3.y,
  };
}

/** 同一代码块里问题越多，线越淡，避免满屏箭头 */
export function connectorOpacity(count: number) {
  return clamp(1 - 0.12 * (count - 1), 0.35, 1);
}

export function emptyDoc(id: string, title: string): CanvasDoc {
  return {
    id,
    title,
    blocks: [],
    questions: [],
    viewport: { x: 120, y: 120, zoom: 1 },
  };
}
