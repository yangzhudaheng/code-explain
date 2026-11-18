import type { Viewport } from '../model/types';

/**
 * 视口裁剪。
 *
 * <p>画布上节点一多（几十个代码块 + 上百个回答框），即使只平移视野，
 * 所有回答框里的 Markdown 都要参与栅格化，帧率会掉到 40 上下。
 * 这里把「世界坐标里的可视范围」算出来，离得远的节点直接不挂载。
 *
 * <p>为什么不担心卸载丢状态：回答框高度（boxH）和锚点几何（anchorPoint /
 * anchorRects）都存在画布快照里，重新挂载时 CodeBlock 的布局副作用会重测，
 * 没有只活在 DOM 里的状态。
 */
export interface WorldRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/**
 * 代码块的高度上界，用来判断它是否完全在视野外。
 * `.code-scroll` 的 max-height 是 520px，加 30px 标题栏还有余量。
 */
export const BLOCK_MAX_H = 560;

/** 回答框高度未知时（还没量过）按这个值算，宁大勿小，避免把可见的框裁掉 */
const ANSWER_MIN_H = 320;

/** 可视区四周额外保留的屏幕像素：平移时不至于一进边界就整块挂载/卸载 */
const SLACK_PX = 420;

/** 屏幕可视区 → 世界坐标矩形（含 slack） */
export function visibleWorldRect(
  size: { w: number; h: number },
  vp: Viewport,
  slackPx = SLACK_PX,
): WorldRect {
  const z = vp.zoom;
  return {
    left: (-vp.x - slackPx) / z,
    top: (-vp.y - slackPx) / z,
    right: (size.w - vp.x + slackPx) / z,
    bottom: (size.h - vp.y + slackPx) / z,
  };
}

function intersects(
  x: number,
  y: number,
  w: number,
  h: number,
  view: WorldRect,
): boolean {
  return x + w >= view.left && x <= view.right && y + h >= view.top && y <= view.bottom;
}

export function blockVisible(
  block: { x: number; y: number; width: number },
  view: WorldRect,
): boolean {
  return intersects(block.x, block.y, block.width, BLOCK_MAX_H, view);
}

export function answerVisible(
  q: { boxX: number; boxY: number; boxH: number; status: string },
  view: WorldRect,
): boolean {
  // 正在流式输出的框不裁：它是用户此刻的注意力焦点，宁可多渲染一个
  if (q.status === 'streaming') return true;
  return intersects(q.boxX, q.boxY, 320, Math.max(q.boxH || 0, ANSWER_MIN_H), view);
}
