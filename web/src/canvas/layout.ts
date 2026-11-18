import type { CanvasDoc, CodeBlockModel, Question, Side, Viewport } from '../model/types';

export const COL_OFFSET = 140;
export const BOX_GAP = 16;
export const BOX_WIDTH = 320;
export const DEFAULT_BOX_H = 110;
/** 侧栏宽度（与 styles.css 的 .sidebar 保持一致） */
export const SIDEBAR_W = 216;
/** 布局时留出的最小可视边距 */
const MIN_ROOM = 8;

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

/** 某一侧的「回答列」在世界坐标里的左上角 x */
export function columnX(block: CodeBlockModel, side: Side) {
  return side === 'right'
    ? block.x + block.width + COL_OFFSET
    : block.x - COL_OFFSET - BOX_WIDTH;
}

/** 连线在代码块上的起点 x：右侧贴右边界，左侧贴左边界 */
export function anchorEdgeX(block: CodeBlockModel, side: Side) {
  return side === 'right' ? block.x + block.width : block.x;
}

/** 连线在回答框上的落点 x：右侧框取左边界，左侧框取右边界 */
export function boxEdgeX(q: Question, side: Side) {
  return side === 'right' ? q.boxX : q.boxX + BOX_WIDTH;
}

/**
 * 自动判断回答框应该放哪一侧。
 * 优先级：先保证整列能落在可视区域内，两侧都放得下再按选中位置偏向哪边决定。
 * 这样既照顾「选中位置在哪边」的直觉，又不会把回答框甩到屏幕外。
 */
export function chooseSide(input: {
  block: CodeBlockModel;
  viewport: Viewport;
  containerRect: DOMRect;
  anchorScreenX: number;
}): Side {
  const { block, viewport, containerRect, anchorScreenX } = input;
  const toScreenX = (wx: number) => containerRect.left + viewport.x + wx * viewport.zoom;
  const rightEdge = toScreenX(block.x + block.width + COL_OFFSET + BOX_WIDTH);
  const leftEdge = toScreenX(block.x - COL_OFFSET - BOX_WIDTH);

  const fitsRight = rightEdge <= containerRect.right - MIN_ROOM;
  const fitsLeft = leftEdge >= containerRect.left + SIDEBAR_W + MIN_ROOM;

  const roomRight = containerRect.right - anchorScreenX;
  const roomLeft = anchorScreenX - (containerRect.left + SIDEBAR_W);

  if (fitsRight && fitsLeft) return roomRight >= roomLeft ? 'right' : 'left';
  if (fitsRight) return 'right';
  if (fitsLeft) return 'left';
  return roomRight >= roomLeft ? 'right' : 'left';
}

/**
 * 分侧布局 + 贪心避让。
 * 规则：
 * - 回答框按 (代码块, 侧) 分组，各自占一列
 *   右侧列 = 代码块右边界 + COL_OFFSET，左侧列 = 代码块左边界 - COL_OFFSET - BOX_WIDTH
 * - 组内按锚点 y 排序后依次下推，间距 16
 * - pinned 的框完全不参与重排（用户的意图优先）
 *
 * <p>**没被移动的框必须返回同一个对象引用**。拖动时每帧都会调一次 arrange，
 * 如果照旧无脑 `map(q => ({...q}))`，150 个回答框每帧全部换新身份，
 * React.memo 全部失效，等同于每帧重渲染整张画布 —— 这是拖动掉帧的主因。
 * 位置变化的判定用 0.5px 容差：亚像素抖动不值得让一整个框重渲染。
 */
export function arrange(questions: Question[], blocks: CodeBlockModel[]): Question[] {
  const groups = new Map<string, Question[]>();
  for (const q of questions) {
    const key = `${q.blockId}|${q.side}`;
    const list = groups.get(key) ?? [];
    list.push(q);
    groups.set(key, list);
  }

  const target = new Map<string, { x: number; y: number }>();
  for (const [key, list] of groups) {
    const [blockId, side] = key.split('|') as [string, Side];
    const block = blocks.find((b) => b.id === blockId);
    const colX = block ? columnX(block, side) : side === 'right' ? COL_OFFSET : -BOX_WIDTH - COL_OFFSET;

    // list 是本函数内新建的数组，原地排序不会影响调用方传进来的 questions
    list.sort((a, b) => a.anchorPoint.y - b.anchorPoint.y);

    // 锁定的框是固定障碍。以前只让它们「推进游标」，一旦用户把框拖到更靠前的框上面，
    // 那个框已经排完不会再动，两者就叠在一起了。先把锁定框的纵向区间收出来，
    // 排未锁定的框时逐个跳过，谁也不会被压住。
    const obstacles = list
      .filter((q) => q.pinned)
      .map((q) => ({ top: q.boxY, bottom: q.boxY + (q.boxH || DEFAULT_BOX_H) }));

    let cursor = Number.NEGATIVE_INFINITY;

    for (const q of list) {
      if (q.pinned) continue;
      const h = q.boxH || DEFAULT_BOX_H;
      const desired = q.anchorPoint.y - 24;
      let y = cursor === Number.NEGATIVE_INFINITY ? desired : Math.max(desired, cursor);

      // 下推到不再压住任何锁定框为止（障碍是固定的，循环必然收敛）
      for (let moved = true; moved; ) {
        moved = false;
        for (const o of obstacles) {
          if (y < o.bottom + BOX_GAP && y + h > o.top) {
            y = o.bottom + BOX_GAP;
            moved = true;
          }
        }
      }

      target.set(q.id, { x: colX, y });
      cursor = y + h + BOX_GAP;
    }
  }

  let changed = false;
  const next = questions.map((q) => {
    const t = target.get(q.id);
    if (!t) return q;
    if (Math.abs(q.boxX - t.x) < 0.5 && Math.abs(q.boxY - t.y) < 0.5) return q;
    changed = true;
    return { ...q, boxX: t.x, boxY: t.y };
  });

  return changed ? next : questions;
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
    version: 0,
    blocks: [],
    questions: [],
    viewport: { x: 120, y: 120, zoom: 1 },
    bg: { pattern: 'dot', tone: 'warm' },
  };
}
